package com.trijohn.cloudportal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object DownloadTimePolicy {
    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
    private const val RECENT_WINDOW_MILLIS = 10 * MINUTE_MILLIS

    fun isRecent(completedAtMillis: Long, nowMillis: Long): Boolean {
        if (completedAtMillis <= 0L) return false
        return (nowMillis - completedAtMillis).coerceAtLeast(0L) < RECENT_WINDOW_MILLIS
    }

    fun label(completedAtMillis: Long, nowMillis: Long): String? {
        if (completedAtMillis <= 0L) return null
        val elapsed = (nowMillis - completedAtMillis).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE_MILLIS -> "Vừa xong"
            elapsed < HOUR_MILLIS -> "${elapsed / MINUTE_MILLIS} phút trước"
            elapsed < DAY_MILLIS -> "${elapsed / HOUR_MILLIS} giờ trước"
            else -> SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.forLanguageTag("vi-VN"))
                .format(Date(completedAtMillis))
        }
    }
}
