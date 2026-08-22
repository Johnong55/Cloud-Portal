package com.trijohn.cloudportal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTimePolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun labelsRecentCompletionsInVietnamese() {
        assertEquals("Vừa xong", DownloadTimePolicy.label(now - 30_000L, now))
        assertEquals("7 phút trước", DownloadTimePolicy.label(now - 7 * 60_000L, now))
        assertEquals("3 giờ trước", DownloadTimePolicy.label(now - 3 * 3_600_000L, now))
    }

    @Test
    fun marksOnlyFirstTenMinutesAsRecent() {
        assertTrue(DownloadTimePolicy.isRecent(now - 9 * 60_000L, now))
        assertFalse(DownloadTimePolicy.isRecent(now - 10 * 60_000L, now))
        assertFalse(DownloadTimePolicy.isRecent(0L, now))
    }

    @Test
    fun doesNotLabelUnknownCompletionTime() {
        assertNull(DownloadTimePolicy.label(0L, now))
    }
}
