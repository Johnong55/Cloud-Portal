package com.trijohn.cloudportal

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Size
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ExifTransform(
    val flipHorizontal: Boolean,
    val rotationDegrees: Int,
) {
    val isIdentity: Boolean
        get() = !flipHorizontal && rotationDegrees == 0
}

internal object ExifOrientationPolicy {
    fun transform(orientation: Int): ExifTransform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(true, 0)
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(false, 180)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(true, 180)
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(true, 270)
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(false, 90)
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(true, 90)
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(false, 270)
        else -> ExifTransform(false, 0)
    }
}

internal object MediaBitmapDecoder {
    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        requestedSize: Int,
    ): Bitmap? {
        val safeRequestedSize = requestedSize.coerceAtLeast(1)
        return try {
            val transform = readExifTransform(resolver, uri)
            val bounds = readBounds(resolver, uri)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = calculateSampleSize(bounds.width, bounds.height, safeRequestedSize)
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return fallbackThumbnail(resolver, uri, safeRequestedSize)

            val oriented = applyTransform(decoded, transform)
            scaleDown(oriented, safeRequestedSize)
        } catch (_: Exception) {
            fallbackThumbnail(resolver, uri, safeRequestedSize)
        }
    }

    internal fun calculateSampleSize(width: Int, height: Int, requestedSize: Int): Int {
        if (width <= 0 || height <= 0 || requestedSize <= 0) return 1
        return (max(width, height) / requestedSize).coerceAtLeast(1)
    }

    private fun readBounds(resolver: ContentResolver, uri: Uri): Size {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return Size(options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0))
    }

    private fun readExifTransform(resolver: ContentResolver, uri: Uri): ExifTransform {
        val orientation = try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return ExifOrientationPolicy.transform(orientation)
    }

    private fun applyTransform(source: Bitmap, transform: ExifTransform): Bitmap {
        if (transform.isIdentity) return source
        val matrix = Matrix().apply {
            if (transform.flipHorizontal) postScale(-1f, 1f)
            if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
        }
        val transformed = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (transformed !== source) source.recycle()
        return transformed
    }

    private fun scaleDown(source: Bitmap, requestedSize: Int): Bitmap {
        val largestSide = max(source.width, source.height)
        if (largestSide <= requestedSize) return source
        val ratio = requestedSize.toFloat() / largestSide
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = source.scale(width, height)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun fallbackThumbnail(
        resolver: ContentResolver,
        uri: Uri,
        requestedSize: Int,
    ): Bitmap? = try {
        resolver.loadThumbnail(uri, Size(requestedSize, requestedSize), null)
    } catch (_: Exception) {
        null
    }
}
