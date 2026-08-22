package com.trijohn.cloudportal

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
) {
    val progress: Float?
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

class DownloadRepository(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun enqueue(details: WebDownloadRequest): Result<String> = runCatching {
        require(ICloudUrlPolicy.isAllowed(details.url)) { "Liên kết tải xuống không thuộc máy chủ Apple." }

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

    fun listDownloads(): List<CloudDownload> {
        recoverLegacyArchivesOnce()
        val saved = readSavedDownloads()
        if (saved.isEmpty()) return emptyList()

        val resultById = mutableMapOf<Long, CloudDownload>()
        val archivesToSchedule = mutableListOf<Long>()
        val query = DownloadManager.Query().setFilterById(*saved.map { it.id }.toLongArray())
        manager.query(query)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

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
                )
            }
        }

        archivesToSchedule.forEach(::scheduleArchiveExtraction)

        return saved.asReversed().map { stored ->
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
            )
        }
    }

    fun openDownload(download: CloudDownload): Boolean {
        if (download.state == DownloadState.Extracted) {
            val directory = download.extractedDirectory ?: return false
            return openExtractedDirectory(directory)
        }

        val uri = manager.getUriForDownloadedFile(download.id) ?: return false
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

    fun retryExtraction(download: CloudDownload): Boolean {
        if (!ArchiveFilePolicy.isZip(download.fileName, download.mimeType)) return false
        val updated = updateSavedDownload(download.id) {
            it.copy(extractionState = ExtractionState.Pending, extractedFileCount = 0)
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
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_ENTRY_BYTES) { "Một tệp trong ZIP quá lớn." }
                            require(totalBytes <= MAX_ARCHIVE_BYTES) { "ZIP giải nén vượt giới hạn an toàn." }
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

    private fun createPendingDownload(fileName: String, relativeDirectory: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, ArchiveFilePolicy.mimeType(fileName))
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
        context.contentResolver.update(uri, values, null, null)
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
            "icloud-archive-$id",
            policy,
            request,
        )
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
        return SavedDownload(
            id,
            fileName,
            mimeType,
            extractionState,
            extractedDirectory,
            extractedFileCount,
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
            ).joinToString(SEPARATOR)
        }
        preferences.edit(commit = true) { putStringSet(KEY_DOWNLOADS, records) }
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
        const val KEY_ARCHIVE_RECOVERY_COMPLETE = "archive_recovery_v2_2_complete"
        const val SEPARATOR = "\t"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ARCHIVE_ENTRIES = 20_000
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 40L * 1024 * 1024 * 1024
        val RECORD_LOCK = Any()
    }
}
