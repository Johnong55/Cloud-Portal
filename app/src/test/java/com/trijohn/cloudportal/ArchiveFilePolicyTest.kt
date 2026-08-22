package com.trijohn.cloudportal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFilePolicyTest {
    @Test
    fun detectsZipByExtensionOrMimeType() {
        assertTrue(ArchiveFilePolicy.isZip("iCloud Photos.ZIP", "application/octet-stream"))
        assertTrue(ArchiveFilePolicy.isZip("download", "application/zip"))
        assertFalse(ArchiveFilePolicy.isZip("IMG_0001.HEIC", "image/heic"))
    }

    @Test
    fun stripsDirectoriesAndUnsafeCharactersFromEntries() {
        assertEquals("secret.jpg", ArchiveFilePolicy.safeEntryFileName("../../private/secret.jpg"))
        assertEquals("photo_name_.heic", ArchiveFilePolicy.safeEntryFileName("album/photo:name?.heic"))
        assertEquals("icloud-file", ArchiveFilePolicy.safeEntryFileName("../"))
    }

    @Test
    fun givesFlattenedDuplicateEntriesUniqueNames() {
        val used = mutableSetOf<String>()
        assertEquals("IMG_0001.JPG", ArchiveFilePolicy.uniqueFileName("IMG_0001.JPG", used))
        assertEquals("IMG_0001_2.JPG", ArchiveFilePolicy.uniqueFileName("IMG_0001.JPG", used))
        assertEquals("IMG_0001_3.jpg", ArchiveFilePolicy.uniqueFileName("IMG_0001.jpg", used))
    }
}
