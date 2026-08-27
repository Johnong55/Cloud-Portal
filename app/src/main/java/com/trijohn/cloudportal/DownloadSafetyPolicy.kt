package com.trijohn.cloudportal

import kotlin.math.max

internal object DownloadSafetyPolicy {
    const val MAX_ARCHIVE_BYTES = 40L * 1_024 * 1_024 * 1_024
    const val MAX_DIRECT_DOWNLOAD_BYTES = 40L * 1_024 * 1_024 * 1_024
    const val MINIMUM_FREE_SPACE_BYTES = 512L * 1_024 * 1_024

    private const val MAX_COMPRESSION_RATIO = 30L
    private const val MINIMUM_EXTRACTION_BUDGET_BYTES = 256L * 1_024 * 1_024

    fun extractionLimit(archiveBytes: Long?, availableBytes: Long): Long {
        val storageBudget = (availableBytes - MINIMUM_FREE_SPACE_BYTES).coerceAtLeast(0L)
        val expansionBudget = archiveBytes?.takeIf { it > 0L }?.let { size ->
            if (size > MAX_ARCHIVE_BYTES / MAX_COMPRESSION_RATIO) {
                MAX_ARCHIVE_BYTES
            } else {
                max(MINIMUM_EXTRACTION_BUDGET_BYTES, size * MAX_COMPRESSION_RATIO)
            }
        } ?: MAX_ARCHIVE_BYTES
        return minOf(MAX_ARCHIVE_BYTES, storageBudget, expansionBudget)
    }
}
