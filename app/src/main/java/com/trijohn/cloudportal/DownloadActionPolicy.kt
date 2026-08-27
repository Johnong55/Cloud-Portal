package com.trijohn.cloudportal

internal object DownloadActionPolicy {
    fun isActive(state: DownloadState): Boolean = state in setOf(
        DownloadState.Pending,
        DownloadState.Running,
        DownloadState.Paused,
        DownloadState.Extracting,
    )

    fun canShare(state: DownloadState): Boolean = state in setOf(
        DownloadState.Complete,
        DownloadState.Extracted,
    )

    fun removalLabel(state: DownloadState): String = if (isActive(state)) "Hủy" else "Xóa"
}
