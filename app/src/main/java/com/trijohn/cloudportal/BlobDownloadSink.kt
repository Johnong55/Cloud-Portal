package com.trijohn.cloudportal

import java.io.OutputStream

/** Receives one iCloud `blob:` download without retaining the whole file in memory. */
internal class BlobDownloadSink(
    val fileName: String,
    initialTotalBytes: Long,
    private val output: OutputStream,
    private val onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    private val onComplete: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    private val onFailure: () -> Unit,
) {
    private var downloadedBytes = 0L
    private var totalBytes = initialTotalBytes
    private var lastPersistedBytes = 0L
    private var closed = false

    @Synchronized
    fun updateTotalBytes(value: Long): Result<Unit> = runCatching {
        check(!closed) { "Luồng tải xuống đã kết thúc." }
        if (value > 0L) totalBytes = value
        onProgress(downloadedBytes, totalBytes)
    }

    @Synchronized
    fun write(bytes: ByteArray): Result<Unit> = runCatching {
        check(!closed) { "Luồng tải xuống đã kết thúc." }
        output.write(bytes)
        downloadedBytes += bytes.size
        if (downloadedBytes - lastPersistedBytes >= PROGRESS_PERSIST_INTERVAL_BYTES) {
            lastPersistedBytes = downloadedBytes
            onProgress(downloadedBytes, totalBytes)
        }
    }

    @Synchronized
    fun complete(): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("Luồng tải xuống đã kết thúc."))
        closed = true
        return runCatching {
            output.flush()
            output.close()
            onComplete(downloadedBytes, totalBytes.takeIf { it > 0L } ?: downloadedBytes)
        }.onFailure {
            runCatching { output.close() }
            onFailure()
        }
    }

    @Synchronized
    fun fail() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        onFailure()
    }

    private companion object {
        const val PROGRESS_PERSIST_INTERVAL_BYTES = 4L * 1024 * 1024
    }
}
