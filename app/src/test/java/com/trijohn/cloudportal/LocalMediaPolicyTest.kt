package com.trijohn.cloudportal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaPolicyTest {
    @Test
    fun `recognizes image and video MIME types`() {
        assertEquals(LocalMediaKind.Image, LocalMediaPolicy.kind("image/heic", "icloud-file"))
        assertEquals(LocalMediaKind.Video, LocalMediaPolicy.kind("video/quicktime", "icloud-file"))
    }

    @Test
    fun `falls back to common Apple media extensions`() {
        assertEquals(LocalMediaKind.Image, LocalMediaPolicy.kind("application/octet-stream", "IMG_1.HEIC"))
        assertEquals(LocalMediaKind.Video, LocalMediaPolicy.kind(null, "IMG_2.MOV"))
        assertEquals("image/heic", LocalMediaPolicy.normalizedMimeType(null, "IMG_1.HEIC"))
        assertEquals("video/quicktime", LocalMediaPolicy.normalizedMimeType("application/octet-stream", "IMG_2.MOV"))
        assertNull(LocalMediaPolicy.kind("application/pdf", "document.pdf"))
    }
}
