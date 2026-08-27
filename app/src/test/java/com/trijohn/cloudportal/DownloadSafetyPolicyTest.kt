package com.trijohn.cloudportal

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSafetyPolicyTest {
    @Test
    fun `always reserves free space on the device`() {
        assertEquals(
            0L,
            DownloadSafetyPolicy.extractionLimit(
                archiveBytes = 20L * MIB,
                availableBytes = 400L * MIB,
            ),
        )
    }

    @Test
    fun `allows normal photo archives without unlimited expansion`() {
        assertEquals(
            300L * MIB,
            DownloadSafetyPolicy.extractionLimit(
                archiveBytes = 10L * MIB,
                availableBytes = 2L * GIB,
            ),
        )
    }

    @Test
    fun `caps unknown or huge archives at the global limit`() {
        assertEquals(
            DownloadSafetyPolicy.MAX_ARCHIVE_BYTES,
            DownloadSafetyPolicy.extractionLimit(
                archiveBytes = null,
                availableBytes = 80L * GIB,
            ),
        )
    }

    private companion object {
        const val MIB = 1_024L * 1_024
        const val GIB = 1_024L * MIB
    }
}
