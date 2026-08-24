package com.trijohn.cloudportal

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationPolicyTest {
    @Test
    fun `maps every EXIF orientation to the expected visual transform`() {
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to ExifTransform(false, 0),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to ExifTransform(true, 0),
            ExifInterface.ORIENTATION_ROTATE_180 to ExifTransform(false, 180),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to ExifTransform(true, 180),
            ExifInterface.ORIENTATION_TRANSPOSE to ExifTransform(true, 270),
            ExifInterface.ORIENTATION_ROTATE_90 to ExifTransform(false, 90),
            ExifInterface.ORIENTATION_TRANSVERSE to ExifTransform(true, 90),
            ExifInterface.ORIENTATION_ROTATE_270 to ExifTransform(false, 270),
        )

        expected.forEach { (orientation, transform) ->
            assertEquals(transform, ExifOrientationPolicy.transform(orientation))
        }
    }

    @Test
    fun `treats undefined orientation as normal`() {
        assertEquals(
            ExifTransform(false, 0),
            ExifOrientationPolicy.transform(ExifInterface.ORIENTATION_UNDEFINED),
        )
    }

    @Test
    fun `samples large images without upscaling small images`() {
        assertEquals(7, MediaBitmapDecoder.calculateSampleSize(4_032, 3_024, 560))
        assertEquals(1, MediaBitmapDecoder.calculateSampleSize(480, 320, 560))
    }
}
