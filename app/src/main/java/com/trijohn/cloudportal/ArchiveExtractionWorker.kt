package com.trijohn.cloudportal

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ArchiveExtractionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId < 0L) return Result.failure()
        return DownloadRepository(applicationContext)
            .extractDownloadedArchive(downloadId)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.failure() },
            )
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
    }
}
