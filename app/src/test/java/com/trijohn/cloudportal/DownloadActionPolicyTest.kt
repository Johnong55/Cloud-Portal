package com.trijohn.cloudportal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionPolicyTest {
    @Test
    fun `active downloads can be cancelled`() {
        assertTrue(DownloadActionPolicy.isActive(DownloadState.Pending))
        assertTrue(DownloadActionPolicy.isActive(DownloadState.Running))
        assertTrue(DownloadActionPolicy.isActive(DownloadState.Paused))
        assertTrue(DownloadActionPolicy.isActive(DownloadState.Extracting))
        assertEquals("Hủy", DownloadActionPolicy.removalLabel(DownloadState.Running))
    }

    @Test
    fun `completed downloads can be shared and deleted`() {
        assertTrue(DownloadActionPolicy.canShare(DownloadState.Complete))
        assertTrue(DownloadActionPolicy.canShare(DownloadState.Extracted))
        assertFalse(DownloadActionPolicy.isActive(DownloadState.Complete))
        assertEquals("Xóa", DownloadActionPolicy.removalLabel(DownloadState.Complete))
    }

    @Test
    fun `failed and missing downloads are not shareable`() {
        assertFalse(DownloadActionPolicy.canShare(DownloadState.Failed))
        assertFalse(DownloadActionPolicy.canShare(DownloadState.ExtractionFailed))
        assertFalse(DownloadActionPolicy.canShare(DownloadState.Missing))
    }
}
