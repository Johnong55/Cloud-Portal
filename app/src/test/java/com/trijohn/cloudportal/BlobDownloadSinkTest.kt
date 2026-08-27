package com.trijohn.cloudportal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class BlobDownloadSinkTest {
    @Test
    fun `streams chunks and reports final byte counts`() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long>>()
        var completion: Pair<Long, Long>? = null
        var failures = 0
        val sink = BlobDownloadSink(
            fileName = "video.mov",
            initialTotalBytes = -1L,
            output = output,
            onProgress = { downloaded, total -> progress += downloaded to total },
            onComplete = { downloaded, total -> completion = downloaded to total },
            onFailure = { failures++ },
        )

        assertTrue(sink.updateTotalBytes(5L).isSuccess)
        assertTrue(sink.write(byteArrayOf(1, 2)).isSuccess)
        assertTrue(sink.write(byteArrayOf(3, 4, 5)).isSuccess)
        assertTrue(sink.complete().isSuccess)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), output.toByteArray())
        assertEquals(listOf(0L to 5L), progress)
        assertEquals(5L to 5L, completion)
        assertEquals(0, failures)
    }

    @Test
    fun `cleans up when publishing completed file fails`() {
        var failures = 0
        val sink = BlobDownloadSink(
            fileName = "video.mov",
            initialTotalBytes = 3L,
            output = ByteArrayOutputStream(),
            onProgress = { _, _ -> },
            onComplete = { _, _ -> throw IOException("publish failed") },
            onFailure = { failures++ },
        )

        assertTrue(sink.write(byteArrayOf(1, 2, 3)).isSuccess)
        assertTrue(sink.complete().isFailure)
        assertEquals(1, failures)
        assertTrue(sink.write(byteArrayOf(4)).isFailure)
    }

    @Test
    fun `failure closes output and only notifies once`() {
        var closed = false
        var failures = 0
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                closed = true
            }
        }
        val sink = BlobDownloadSink(
            fileName = "video.mov",
            initialTotalBytes = -1L,
            output = output,
            onProgress = { _, _ -> },
            onComplete = { _, _ -> },
            onFailure = { failures++ },
        )

        sink.fail()
        sink.fail()

        assertTrue(closed)
        assertEquals(1, failures)
        assertTrue(sink.write(byteArrayOf(1)).isFailure)
    }

    @Test
    fun `rejects oversized and incomplete blob transfers`() {
        var failures = 0
        val sink = BlobDownloadSink(
            fileName = "video.mov",
            initialTotalBytes = -1L,
            maximumBytes = 4L,
            output = ByteArrayOutputStream(),
            onProgress = { _, _ -> },
            onComplete = { _, _ -> },
            onFailure = { failures++ },
        )

        assertTrue(sink.updateTotalBytes(5L).isFailure)
        assertTrue(sink.updateTotalBytes(4L).isSuccess)
        assertTrue(sink.write(byteArrayOf(1, 2, 3)).isSuccess)
        assertTrue(sink.complete().isFailure)
        assertEquals(1, failures)
    }
}
