package com.trijohn.cloudportal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters

class ArchiveExtractionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId < 0L) return Result.failure()
        setForegroundAsync(createForegroundInfo(downloadId)).get()
        return DownloadRepository(applicationContext)
            .extractDownloadedArchive(downloadId)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.failure() },
            )
    }

    private fun createForegroundInfo(downloadId: Long): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Giải nén ảnh iCloud",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Hiển thị khi Cloud Portal đang giải nén tệp tải xuống."
            },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Cloud Portal")
            .setContentText("Đang giải nén ảnh iCloud vào Downloads…")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (downloadId xor (downloadId ushr 32)).toInt().and(0x0FFF)
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val CHANNEL_ID = "icloud_archive_extraction"
        private const val NOTIFICATION_ID_BASE = 4_200
    }
}
