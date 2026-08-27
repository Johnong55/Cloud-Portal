package com.trijohn.cloudportal

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

data class WebDownloadRequest(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
)

enum class DownloadState {
    Pending,
    Running,
    Paused,
    Complete,
    Extracting,
    Extracted,
    ExtractionFailed,
    Failed,
    Missing,
}

data class CloudDownload(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val extractedDirectory: String? = null,
    val extractedFileCount: Int = 0,
    val completedAtMillis: Long = 0L,
    val directUri: String? = null,
) {
    val progress: Float?
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

enum class LocalMediaKind {
    Image,
    Video,
}

data class LocalMedia(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val kind: LocalMediaKind,
    val sizeBytes: Long,
    val completedAtMillis: Long,
    val relativePath: String? = null,
)

class DownloadRepository(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val activeDirectDownloadIds = mutableSetOf<Long>()
    private val activeDirectDownloadSinks = mutableMapOf<Long, BlobDownloadSink>()

    fun enqueue(details: WebDownloadRequest): Result<String> = runCatching {
        require(ICloudUrlPolicy.isAllowed(details.url)) {
            "Không thể tải từ nguồn ${ICloudUrlPolicy.sourceLabel(details.url)}."
        }

        val guessedName = URLUtil.guessFileName(
            details.url,
            details.contentDisposition,
            details.mimeType,
        )
        val fileName = createUniqueFileName(guessedName)
        val mimeType = details.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        val request = DownloadManager.Request(details.url.toUri())
            .setTitle(fileName)
            .setDescription("Đang tải từ iCloud")
            .setMimeType(mimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        if (details.userAgent.isNotBlank()) {
            request.addRequestHeader("User-Agent", details.userAgent)
        }
        CookieManager.getInstance().getCookie(details.url)
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("Cookie", it) }

        val id = manager.enqueue(request)
        rememberDownload(id, fileName, mimeType)
        fileName
    }

    /** Creates a MediaStore target for an iCloud `blob:` URL streamed by the WebView bridge. */
    internal fun beginBlobDownload(details: WebDownloadRequest): Result<BlobDownloadSink> = runCatching {
        require(ICloudUrlPolicy.isTrustedBlob(details.url)) {
            "Không thể tải từ nguồn ${ICloudUrlPolicy.sourceLabel(details.url)}."
        }

        val guessedName = URLUtil.guessFileName(
            ICLOUD_DOWNLOAD_FALLBACK_URL,
            details.contentDisposition,
            details.mimeType,
        )
        val fileName = createUniqueFileName(guessedName)
        val mimeType = details.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val uri = createPendingDownload(
            fileName = fileName,
            relativeDirectory = "${Environment.DIRECTORY_DOWNLOADS}/Cloud Portal/",
            mimeType = mimeType,
        )
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: run {
                context.contentResolver.delete(uri, null, null)
                error("Không thể tạo tệp $fileName trong Downloads.")
            }
        val id = nextDirectDownloadId()
        val initialRecord = DirectDownload(
            id = id,
            fileName = fileName,
            mimeType = mimeType,
            state = DirectDownloadState.Running,
            uri = uri.toString(),
            downloadedBytes = 0L,
            totalBytes = details.contentLength,
            createdAtMillis = System.currentTimeMillis(),
            completedAtMillis = 0L,
        )

        synchronized(activeDirectDownloadIds) { activeDirectDownloadIds += id }
        try {
            rememberDirectDownload(initialRecord)
        } catch (error: Throwable) {
            synchronized(activeDirectDownloadIds) { activeDirectDownloadIds -= id }
            runCatching { output.close() }
            context.contentResolver.delete(uri, null, null)
            throw error
        }

        val sink = BlobDownloadSink(
            fileName = fileName,
            initialTotalBytes = details.contentLength,
            maximumBytes = DownloadSafetyPolicy.MAX_DIRECT_DOWNLOAD_BYTES,
            output = output,
            onProgress = { downloadedBytes, totalBytes ->
                updateDirectDownload(id) {
                    it.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes)
                }
            },
            onComplete = { downloadedBytes, totalBytes ->
                publishDownload(uri)
                updateDirectDownload(id) {
                    it.copy(
                        state = DirectDownloadState.Complete,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        completedAtMillis = System.currentTimeMillis(),
                    )
                }
                synchronized(activeDirectDownloadIds) { activeDirectDownloadIds -= id }
                synchronized(activeDirectDownloadSinks) { activeDirectDownloadSinks -= id }
            },
            onFailure = {
                runCatching { context.contentResolver.delete(uri, null, null) }
                updateDirectDownload(id) {
                    it.copy(
                        state = DirectDownloadState.Failed,
                        uri = "",
                        completedAtMillis = 0L,
                    )
                }
                synchronized(activeDirectDownloadIds) { activeDirectDownloadIds -= id }
                synchronized(activeDirectDownloadSinks) { activeDirectDownloadSinks -= id }
            },
        )
        synchronized(activeDirectDownloadSinks) { activeDirectDownloadSinks[id] = sink }
        sink
    }

    fun listDownloads(): List<CloudDownload> {
        recoverInterruptedDirectDownloads()
        recoverLegacyArchivesOnce()
        val directDownloads = readDirectDownloads()
            .sortedByDescending { it.createdAtMillis }
            .map(DirectDownload::toCloudDownload)
        val saved = readSavedDownloads()
        if (saved.isEmpty()) return directDownloads

        val resultById = mutableMapOf<Long, CloudDownload>()
        val archivesToSchedule = mutableListOf<Long>()
        val query = DownloadManager.Query().setFilterById(*saved.map { it.id }.toLongArray())
        manager.query(query)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val lastModifiedIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val stored = saved.firstOrNull { it.id == id } ?: continue
                val managerState = cursor.getInt(statusIndex).toDownloadState()
                if (
                    managerState == DownloadState.Complete &&
                    stored.extractionState in setOf(ExtractionState.Pending, ExtractionState.Extracting)
                ) {
                    archivesToSchedule += id
                }
                resultById[id] = CloudDownload(
                    id = id,
                    fileName = stored.fileName,
                    mimeType = manager.getMimeTypeForDownloadedFile(id) ?: stored.mimeType,
                    state = stored.displayState(managerState),
                    downloadedBytes = cursor.getLong(downloadedIndex).coerceAtLeast(0L),
                    totalBytes = cursor.getLong(totalIndex),
                    extractedDirectory = stored.extractedDirectory,
                    extractedFileCount = stored.extractedFileCount,
                    completedAtMillis = stored.completedAtMillis.takeIf { it > 0L }
                        ?: cursor.getLong(lastModifiedIndex).takeIf { managerState == DownloadState.Complete }
                        ?: 0L,
                )
            }
        }

        archivesToSchedule.forEach(::scheduleArchiveExtraction)

        val managedDownloads = saved.asReversed().map { stored ->
            resultById[stored.id] ?: CloudDownload(
                id = stored.id,
                fileName = stored.fileName,
                mimeType = stored.mimeType,
                state = if (stored.extractionState == ExtractionState.Extracted) {
                    DownloadState.Extracted
                } else {
                    DownloadState.Missing
                },
                downloadedBytes = 0L,
                totalBytes = -1L,
                extractedDirectory = stored.extractedDirectory,
                extractedFileCount = stored.extractedFileCount,
                completedAtMillis = stored.completedAtMillis,
            )
        }
        return directDownloads + managedDownloads
    }

    /** Returns media created by Cloud Portal without copying it into app-private storage. */
    fun listDownloadedMedia(): List<LocalMedia> {
        val mediaByUri = linkedMapOf<String, LocalMedia>()
        queryExtractedMedia().forEach { mediaByUri[it.uri.toString()] = it }

        listDownloads()
            .asSequence()
            .filter { it.state in setOf(DownloadState.Complete, DownloadState.Extracted) }
            .mapNotNull { download ->
                val kind = LocalMediaPolicy.kind(download.mimeType, download.fileName) ?: return@mapNotNull null
                val uri = download.directUri?.takeIf(String::isNotBlank)?.toUri()
                    ?: manager.getUriForDownloadedFile(download.id)
                    ?: return@mapNotNull null
                LocalMedia(
                    uri = uri,
                    fileName = download.fileName,
                    mimeType = LocalMediaPolicy.normalizedMimeType(download.mimeType, download.fileName),
                    kind = kind,
                    sizeBytes = download.downloadedBytes,
                    completedAtMillis = download.completedAtMillis,
                )
            }
            .forEach { mediaByUri.putIfAbsent(it.uri.toString(), it) }

        return mediaByUri.values.sortedWith(
            compareByDescending<LocalMedia> { it.completedAtMillis }.thenBy { it.fileName.lowercase(Locale.ROOT) },
        )
    }

    fun openDownload(download: CloudDownload): Boolean {
        if (download.state == DownloadState.Extracted) {
            val directory = download.extractedDirectory ?: return false
            return openExtractedDirectory(directory)
        }

        val uri = download.directUri?.takeIf { it.isNotBlank() }?.toUri()
            ?: manager.getUriForDownloadedFile(download.id)
            ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, download.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Mở tệp bằng").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun shareDownload(download: CloudDownload): Boolean {
        val uri = download.directUri?.takeIf(String::isNotBlank)?.toUri()
            ?: manager.getUriForDownloadedFile(download.id)
            ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = download.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, download.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(
                Intent.createChooser(intent, "Chia sẻ bằng").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Cancels any active work, deletes its files and removes the matching history record. */
    fun deleteDownload(download: CloudDownload): Result<Unit> = runCatching {
        if (download.id < 0L) {
            synchronized(activeDirectDownloadSinks) { activeDirectDownloadSinks[download.id] }?.fail()
            download.directUri
                ?.takeIf(String::isNotBlank)
                ?.toUri()
                ?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            forgetDirectDownload(download.id)
            synchronized(activeDirectDownloadIds) { activeDirectDownloadIds -= download.id }
            synchronized(activeDirectDownloadSinks) { activeDirectDownloadSinks -= download.id }
            return@runCatching
        }

        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueArchiveWorkName(download.id))
            .result
            .get()
        download.extractedDirectory?.let(::deleteExtractedFiles)
        manager.remove(download.id)
        forgetSavedDownload(download.id)
    }

    /** Deletes one item shown by the native library and reconciles its download record. */
    fun deleteMedia(media: LocalMedia): Result<Unit> = runCatching {
        val directDownload = readDirectDownloads().firstOrNull { it.uri == media.uri.toString() }
        if (directDownload != null) {
            synchronized(activeDirectDownloadSinks) { activeDirectDownloadSinks[directDownload.id] }?.fail()
            context.contentResolver.delete(media.uri, null, null)
            forgetDirectDownload(directDownload.id)
            return@runCatching
        }

        val managedDownload = readSavedDownloads().firstOrNull { saved ->
            manager.getUriForDownloadedFile(saved.id)?.toString() == media.uri.toString()
        }
        if (managedDownload != null) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueArchiveWorkName(managedDownload.id))
                .result
                .get()
            manager.remove(managedDownload.id)
            forgetSavedDownload(managedDownload.id)
            return@runCatching
        }

        check(context.contentResolver.delete(media.uri, null, null) > 0) {
            "Tệp không còn tồn tại hoặc Android không cho phép xóa."
        }
        media.relativePath?.let { relativePath ->
            val matchingDownload = readSavedDownloads().firstOrNull {
                it.extractedDirectory == relativePath
            }
            matchingDownload?.let { saved ->
                updateSavedDownload(saved.id) {
                    it.copy(extractedFileCount = (it.extractedFileCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun queryExtractedMedia(): List<LocalMedia> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Cloud Portal/%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val result = mutableListOf<LocalMedia>()

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameIndex).orEmpty()
                    val storedMimeType = cursor.getString(mimeIndex).orEmpty()
                    val kind = LocalMediaPolicy.kind(storedMimeType, fileName) ?: continue
                    val timestampSeconds = cursor.getLong(modifiedIndex).takeIf { it > 0L }
                        ?: cursor.getLong(addedIndex)
                    result += LocalMedia(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex)),
                        fileName = fileName,
                        mimeType = LocalMediaPolicy.normalizedMimeType(storedMimeType, fileName),
                        kind = kind,
                        sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                        completedAtMillis = timestampSeconds.coerceAtLeast(0L) * 1_000L,
                        relativePath = cursor.getString(pathIndex),
                    )
                }
            }
        }
        return result
    }

    fun retryExtraction(download: CloudDownload): Boolean {
        if (!ArchiveFilePolicy.isZip(download.fileName, download.mimeType)) return false
        val updated = updateSavedDownload(download.id) {
            it.copy(
                extractionState = ExtractionState.Pending,
                extractedFileCount = 0,
                completedAtMillis = 0L,
            )
        } ?: return false
        scheduleArchiveExtraction(updated.id, ExistingWorkPolicy.REPLACE)
        return true
    }

    fun openSystemDownloads(): Boolean = try {
        context.startActivity(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Removes only Cloud Portal's list. Downloaded and extracted files remain in Downloads. */
    fun clearHistory() {
        preferences.edit {
            remove(KEY_DOWNLOADS)
            remove(KEY_DIRECT_DOWNLOADS)
            putBoolean(KEY_ARCHIVE_RECOVERY_COMPLETE, true)
        }
    }

    internal fun handleDownloadComplete(id: Long) {
        val stored = readSavedDownloads().firstOrNull { it.id == id } ?: return
        if (stored.extractionState != ExtractionState.Pending) return

        val query = DownloadManager.Query().setFilterById(id)
        val isComplete = manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false
        if (isComplete) scheduleArchiveExtraction(id)
    }

    internal fun extractDownloadedArchive(id: Long): Result<Int> = runCatching {
        val stored = updateSavedDownload(id) {
            it.copy(extractionState = ExtractionState.Extracting, extractedFileCount = 0)
        } ?: error("Không tìm thấy tệp tải xuống trong lịch sử.")
        require(ArchiveFilePolicy.isZip(stored.fileName, stored.mimeType)) { "Tệp không phải ZIP." }

        val archiveUri = manager.getUriForDownloadedFile(id)
            ?: error("Không thể đọc tệp ZIP đã tải xuống.")
        val relativeDirectory = stored.extractedDirectory
            ?: createExtractionDirectory(stored.fileName)
        val createdUris = mutableListOf<Uri>()
        val usedNames = mutableSetOf<String>()

        try {
            val extractionLimit = calculateExtractionLimit(archiveUri)
            require(extractionLimit > 0L) {
                "Không đủ dung lượng trống để giải nén an toàn."
            }
            val input = context.contentResolver.openInputStream(archiveUri)
                ?: error("Không thể mở tệp ZIP.")
            var entryCount = 0
            var totalBytes = 0L

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "ZIP chứa quá nhiều tệp." }
                    require(entry.size < 0L || entry.size <= MAX_ENTRY_BYTES) { "Một tệp trong ZIP quá lớn." }
                    require(entry.size < 0L || totalBytes + entry.size <= extractionLimit) {
                        "ZIP giải nén vượt dung lượng an toàn của thiết bị."
                    }
                    val safeName = ArchiveFilePolicy.uniqueFileName(
                        ArchiveFilePolicy.safeEntryFileName(entry.name),
                        usedNames,
                    )
                    val outputUri = createPendingDownload(safeName, relativeDirectory)
                    createdUris += outputUri

                    var entryBytes = 0L
                    context.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Đã dừng giải nén." }
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_ENTRY_BYTES) { "Một tệp trong ZIP quá lớn." }
                            require(totalBytes <= extractionLimit) {
                                "ZIP giải nén vượt dung lượng an toàn của thiết bị."
                            }
                            output.write(buffer, 0, read)
                        }
                    } ?: error("Không thể tạo tệp $safeName.")
                    publishDownload(outputUri)
                    zip.closeEntry()
                }
            }

            require(entryCount > 0) { "Tệp ZIP không chứa ảnh hoặc tệp nào." }
            updateSavedDownload(id) {
                it.copy(
                    extractionState = ExtractionState.Extracted,
                    extractedDirectory = relativeDirectory,
                    extractedFileCount = entryCount,
                    completedAtMillis = System.currentTimeMillis(),
                )
            }
            entryCount
        } catch (error: Throwable) {
            createdUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            updateSavedDownload(id) {
                it.copy(
                    extractionState = ExtractionState.Failed,
                    extractedDirectory = relativeDirectory,
                    extractedFileCount = 0,
                )
            }
            throw error
        }
    }

    private fun createPendingDownload(
        fileName: String,
        relativeDirectory: String,
        mimeType: String = ArchiveFilePolicy.mimeType(fileName),
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: error("Không thể tạo tệp trong Downloads.")
    }

    private fun publishDownload(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(context.contentResolver.update(uri, values, null, null) == 1) {
            "Không thể hoàn tất tệp trong Downloads."
        }
    }

    private fun calculateExtractionLimit(archiveUri: Uri): Long {
        val archiveBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(archiveUri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L }
        val availableBytes = runCatching {
            StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
        }.getOrDefault(0L)
        return DownloadSafetyPolicy.extractionLimit(archiveBytes, availableBytes)
    }

    /** Marks process-interrupted blob transfers failed and removes their invisible pending rows. */
    private fun recoverInterruptedDirectDownloads() = synchronized(RECORD_LOCK) {
        val activeIds = synchronized(activeDirectDownloadIds) { activeDirectDownloadIds.toSet() }
        val downloads = readDirectDownloads()
        val interrupted = downloads.filter {
            it.state == DirectDownloadState.Running && it.id !in activeIds
        }
        if (interrupted.isEmpty()) return@synchronized

        interrupted.forEach { download ->
            download.uri.takeIf(String::isNotBlank)?.toUri()?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
        val interruptedIds = interrupted.mapTo(mutableSetOf()) { it.id }
        writeDirectDownloads(
            downloads.map { download ->
                if (download.id in interruptedIds) {
                    download.copy(
                        state = DirectDownloadState.Failed,
                        uri = "",
                        completedAtMillis = 0L,
                    )
                } else {
                    download
                }
            },
        )
    }

    private fun openExtractedDirectory(relativeDirectory: String): Boolean {
        val documentId = "primary:${relativeDirectory.trimEnd('/')}"
        val directoryUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(directoryUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            openSystemDownloads()
        } catch (_: SecurityException) {
            openSystemDownloads()
        }
    }

    private fun scheduleArchiveExtraction(
        id: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val request = OneTimeWorkRequest.Builder(ArchiveExtractionWorker::class.java)
            .setInputData(Data.Builder().putLong(ArchiveExtractionWorker.KEY_DOWNLOAD_ID, id).build())
            .addTag("icloud-archive-extraction")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueArchiveWorkName(id),
            policy,
            request,
        )
    }

    private fun deleteExtractedFiles(relativeDirectory: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ids = mutableListOf<Long>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativeDirectory),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) ids += cursor.getLong(idIndex)
        }
        ids.forEach { id ->
            val uri = ContentUris.withAppendedId(collection, id)
            check(context.contentResolver.delete(uri, null, null) > 0) {
                "Không thể xóa toàn bộ nội dung đã giải nén."
            }
        }
    }

    private fun rememberDownload(id: Long, fileName: String, mimeType: String) {
        val isArchive = ArchiveFilePolicy.isZip(fileName, mimeType)
        val saved = SavedDownload(
            id = id,
            fileName = fileName,
            mimeType = mimeType,
            extractionState = if (isArchive) ExtractionState.Pending else ExtractionState.NotArchive,
            extractedDirectory = if (isArchive) createExtractionDirectory(fileName) else null,
            extractedFileCount = 0,
            completedAtMillis = 0L,
        )
        synchronized(RECORD_LOCK) {
            val downloads = readSavedDownloads().filterNot { it.id == id } + saved
            writeSavedDownloads(downloads)
        }
    }

    /** Imports completed ZIP downloads created by older Cloud Portal builds exactly once. */
    private fun recoverLegacyArchivesOnce() = synchronized(RECORD_LOCK) {
        if (preferences.getBoolean(KEY_ARCHIVE_RECOVERY_COMPLETE, false)) return@synchronized

        val existing = readSavedDownloads()
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        val recovered = mutableListOf<SavedDownload>()
        val recoverySucceeded = runCatching {
            manager.query(DownloadManager.Query())?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val mimeIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    if (id in existingIds) continue
                    if (cursor.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) continue
                    val fileName = cursor.getString(titleIndex).orEmpty()
                    val mimeType = cursor.getString(mimeIndex).orEmpty()
                    if (!ArchiveFilePolicy.isZip(fileName, mimeType)) continue
                    recovered += SavedDownload(
                        id = id,
                        fileName = fileName,
                        mimeType = mimeType.ifBlank { "application/zip" },
                        extractionState = ExtractionState.Pending,
                        extractedDirectory = createExtractionDirectory(fileName),
                        extractedFileCount = 0,
                        completedAtMillis = 0L,
                    )
                }
            }
        }.isSuccess

        if (recoverySucceeded) {
            if (recovered.isNotEmpty()) writeSavedDownloads(existing + recovered)
            preferences.edit(commit = true) { putBoolean(KEY_ARCHIVE_RECOVERY_COMPLETE, true) }
        }
    }

    private fun updateSavedDownload(
        id: Long,
        transform: (SavedDownload) -> SavedDownload,
    ): SavedDownload? = synchronized(RECORD_LOCK) {
        val downloads = readSavedDownloads().toMutableList()
        val index = downloads.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = transform(downloads[index])
        downloads[index] = updated
        writeSavedDownloads(downloads)
        updated
    }

    private fun forgetSavedDownload(id: Long) = synchronized(RECORD_LOCK) {
        writeSavedDownloads(readSavedDownloads().filterNot { it.id == id })
    }

    private fun readSavedDownloads(): List<SavedDownload> = preferences
        .getStringSet(KEY_DOWNLOADS, emptySet())
        .orEmpty()
        .mapNotNull(::decodeSavedDownload)
        .sortedBy { it.id }

    private fun decodeSavedDownload(encoded: String): SavedDownload? {
        val pieces = encoded.split(SEPARATOR)
        if (pieces.size < 3) return null
        val id = pieces[0].toLongOrNull() ?: return null
        val fileName = Uri.decode(pieces[1])
        val mimeType = Uri.decode(pieces[2])
        val isArchive = ArchiveFilePolicy.isZip(fileName, mimeType)
        val extractionState = pieces.getOrNull(3)
            ?.let { runCatching { ExtractionState.valueOf(it) }.getOrNull() }
            ?: if (isArchive) ExtractionState.Pending else ExtractionState.NotArchive
        val extractedDirectory = pieces.getOrNull(4)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::decode)
            ?: if (isArchive) createExtractionDirectory(fileName) else null
        val extractedFileCount = pieces.getOrNull(5)?.toIntOrNull() ?: 0
        val completedAtMillis = pieces.getOrNull(6)?.toLongOrNull() ?: 0L
        return SavedDownload(
            id,
            fileName,
            mimeType,
            extractionState,
            extractedDirectory,
            extractedFileCount,
            completedAtMillis,
        )
    }

    private fun writeSavedDownloads(downloads: List<SavedDownload>) {
        val records = downloads.mapTo(mutableSetOf()) { saved ->
            listOf(
                saved.id.toString(),
                Uri.encode(saved.fileName),
                Uri.encode(saved.mimeType),
                saved.extractionState.name,
                saved.extractedDirectory?.let(Uri::encode).orEmpty(),
                saved.extractedFileCount.toString(),
                saved.completedAtMillis.toString(),
            ).joinToString(SEPARATOR)
        }
        preferences.edit(commit = true) { putStringSet(KEY_DOWNLOADS, records) }
    }

    private fun nextDirectDownloadId(): Long = synchronized(RECORD_LOCK) {
        val usedIds = readDirectDownloads().mapTo(mutableSetOf()) { it.id }
        var candidate = -System.currentTimeMillis()
        while (candidate in usedIds) candidate--
        candidate
    }

    private fun rememberDirectDownload(download: DirectDownload) = synchronized(RECORD_LOCK) {
        val downloads = readDirectDownloads().filterNot { it.id == download.id } + download
        writeDirectDownloads(downloads)
    }

    private fun updateDirectDownload(
        id: Long,
        transform: (DirectDownload) -> DirectDownload,
    ): DirectDownload? = synchronized(RECORD_LOCK) {
        val downloads = readDirectDownloads().toMutableList()
        val index = downloads.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = transform(downloads[index])
        downloads[index] = updated
        writeDirectDownloads(downloads)
        updated
    }

    private fun forgetDirectDownload(id: Long) = synchronized(RECORD_LOCK) {
        writeDirectDownloads(readDirectDownloads().filterNot { it.id == id })
    }

    private fun readDirectDownloads(): List<DirectDownload> = preferences
        .getStringSet(KEY_DIRECT_DOWNLOADS, emptySet())
        .orEmpty()
        .mapNotNull(::decodeDirectDownload)

    private fun decodeDirectDownload(encoded: String): DirectDownload? {
        val pieces = encoded.split(SEPARATOR)
        if (pieces.size < 9) return null
        return DirectDownload(
            id = pieces[0].toLongOrNull() ?: return null,
            fileName = Uri.decode(pieces[1]),
            mimeType = Uri.decode(pieces[2]),
            state = runCatching { DirectDownloadState.valueOf(pieces[3]) }.getOrNull() ?: return null,
            uri = Uri.decode(pieces[4]),
            downloadedBytes = pieces[5].toLongOrNull() ?: 0L,
            totalBytes = pieces[6].toLongOrNull() ?: -1L,
            createdAtMillis = pieces[7].toLongOrNull() ?: 0L,
            completedAtMillis = pieces[8].toLongOrNull() ?: 0L,
        )
    }

    private fun writeDirectDownloads(downloads: List<DirectDownload>) {
        val records = downloads.mapTo(mutableSetOf()) { download ->
            listOf(
                download.id.toString(),
                Uri.encode(download.fileName),
                Uri.encode(download.mimeType),
                download.state.name,
                Uri.encode(download.uri),
                download.downloadedBytes.toString(),
                download.totalBytes.toString(),
                download.createdAtMillis.toString(),
                download.completedAtMillis.toString(),
            ).joinToString(SEPARATOR)
        }
        preferences.edit(commit = true) { putStringSet(KEY_DIRECT_DOWNLOADS, records) }
    }

    private fun createUniqueFileName(guessedName: String): String {
        val sanitized = ArchiveFilePolicy.sanitizeFileName(guessedName)
            .take(120)
            .ifBlank { "icloud-file" }
        val dot = sanitized.lastIndexOf('.')
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (dot in 1 until sanitized.lastIndex) {
            "${sanitized.substring(0, dot)}_$timestamp${sanitized.substring(dot)}"
        } else {
            "${sanitized}_$timestamp"
        }
    }

    private fun createExtractionDirectory(fileName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/Cloud Portal/${ArchiveFilePolicy.archiveBaseName(fileName)}/"

    private data class SavedDownload(
        val id: Long,
        val fileName: String,
        val mimeType: String,
        val extractionState: ExtractionState,
        val extractedDirectory: String?,
        val extractedFileCount: Int,
        val completedAtMillis: Long,
    ) {
        fun displayState(managerState: DownloadState): DownloadState {
            if (managerState != DownloadState.Complete) return managerState
            return when (extractionState) {
                ExtractionState.NotArchive -> DownloadState.Complete
                ExtractionState.Pending, ExtractionState.Extracting -> DownloadState.Extracting
                ExtractionState.Extracted -> DownloadState.Extracted
                ExtractionState.Failed -> DownloadState.ExtractionFailed
            }
        }
    }

    private enum class ExtractionState {
        NotArchive,
        Pending,
        Extracting,
        Extracted,
        Failed,
    }

    private data class DirectDownload(
        val id: Long,
        val fileName: String,
        val mimeType: String,
        val state: DirectDownloadState,
        val uri: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val createdAtMillis: Long,
        val completedAtMillis: Long,
    ) {
        fun toCloudDownload() = CloudDownload(
            id = id,
            fileName = fileName,
            mimeType = mimeType,
            state = when (state) {
                DirectDownloadState.Running -> DownloadState.Running
                DirectDownloadState.Complete -> DownloadState.Complete
                DirectDownloadState.Failed -> DownloadState.Failed
            },
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            completedAtMillis = completedAtMillis,
            directUri = uri,
        )
    }

    private enum class DirectDownloadState {
        Running,
        Complete,
        Failed,
    }

    private fun Int.toDownloadState(): DownloadState = when (this) {
        DownloadManager.STATUS_PENDING -> DownloadState.Pending
        DownloadManager.STATUS_RUNNING -> DownloadState.Running
        DownloadManager.STATUS_PAUSED -> DownloadState.Paused
        DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Complete
        DownloadManager.STATUS_FAILED -> DownloadState.Failed
        else -> DownloadState.Missing
    }

    private companion object {
        const val PREFERENCES = "cloud_portal_downloads"
        const val KEY_DOWNLOADS = "download_records"
        const val KEY_DIRECT_DOWNLOADS = "direct_download_records"
        const val KEY_ARCHIVE_RECOVERY_COMPLETE = "archive_recovery_v2_2_complete"
        const val ICLOUD_DOWNLOAD_FALLBACK_URL = "https://www.icloud.com/download"
        const val SEPARATOR = "\t"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ARCHIVE_ENTRIES = 20_000
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
        val RECORD_LOCK = Any()

        fun uniqueArchiveWorkName(id: Long) = "icloud-archive-$id"
    }
}
