package com.trijohn.cloudportal

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.net.toUri
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val saved = readSavedDownloads()
        if (saved.isEmpty()) return emptyList()

        val resultById = mutableMapOf<Long, CloudDownload>()
        val query = DownloadManager.Query().setFilterById(*saved.map { it.id }.toLongArray())
        manager.query(query)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val stored = saved.firstOrNull { it.id == id } ?: continue
                resultById[id] = CloudDownload(
                    id = id,
                    fileName = stored.fileName,
                    mimeType = manager.getMimeTypeForDownloadedFile(id) ?: stored.mimeType,
                    state = cursor.getInt(statusIndex).toDownloadState(),
                    downloadedBytes = cursor.getLong(downloadedIndex).coerceAtLeast(0L),
                    totalBytes = cursor.getLong(totalIndex),
                )
            }
        }

        return saved.asReversed().map { stored ->
            resultById[stored.id] ?: CloudDownload(
                id = stored.id,
                fileName = stored.fileName,
                mimeType = stored.mimeType,
                state = DownloadState.Missing,
                downloadedBytes = 0L,
                totalBytes = -1L,
            )
        }
    }

    fun openDownload(download: CloudDownload): Boolean {
        val uri = manager.getUriForDownloadedFile(download.id) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, download.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Mở tệp bằng"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun openSystemDownloads(): Boolean = try {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Removes only Cloud Portal's list. Downloaded files remain in Android Downloads. */
    fun clearHistory() {
        preferences.edit { remove(KEY_DOWNLOADS) }
    }

    private fun rememberDownload(id: Long, fileName: String, mimeType: String) {
        val records = preferences.getStringSet(KEY_DOWNLOADS, emptySet()).orEmpty().toMutableSet()
        records += listOf(id.toString(), Uri.encode(fileName), Uri.encode(mimeType)).joinToString(SEPARATOR)
        preferences.edit { putStringSet(KEY_DOWNLOADS, records) }
    }

    private fun readSavedDownloads(): List<SavedDownload> = preferences
        .getStringSet(KEY_DOWNLOADS, emptySet())
        .orEmpty()
        .mapNotNull { encoded ->
            val pieces = encoded.split(SEPARATOR)
            if (pieces.size != 3) return@mapNotNull null
            val id = pieces[0].toLongOrNull() ?: return@mapNotNull null
            SavedDownload(id, Uri.decode(pieces[1]), Uri.decode(pieces[2]))
        }
        .sortedBy { it.id }

    private fun createUniqueFileName(guessedName: String): String {
        val sanitized = guessedName
            .replace(Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]"), "_")
            .trim()
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

    private data class SavedDownload(val id: Long, val fileName: String, val mimeType: String)

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
        const val SEPARATOR = "\t"
    }
}
