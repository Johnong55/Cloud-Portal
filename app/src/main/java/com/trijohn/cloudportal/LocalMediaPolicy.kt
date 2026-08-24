package com.trijohn.cloudportal

import java.util.Locale

internal object LocalMediaPolicy {
    private val mimeTypesByExtension = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "m4v" to "video/x-m4v",
        "3gp" to "video/3gpp",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
    )

    fun kind(mimeType: String?, fileName: String): LocalMediaKind? {
        val normalizedMime = mimeType.orEmpty().lowercase(Locale.ROOT)
        if (normalizedMime.startsWith("image/")) return LocalMediaKind.Image
        if (normalizedMime.startsWith("video/")) return LocalMediaKind.Video
        return mimeTypesByExtension[fileName.extension()]?.let(::kindFromNormalizedMime)
    }

    fun normalizedMimeType(mimeType: String?, fileName: String): String {
        val normalizedMime = mimeType.orEmpty().takeIf {
            it.startsWith("image/", ignoreCase = true) || it.startsWith("video/", ignoreCase = true)
        }
        return normalizedMime
            ?: mimeTypesByExtension[fileName.extension()]
            ?: ArchiveFilePolicy.mimeType(fileName)
    }

    private fun kindFromNormalizedMime(mimeType: String): LocalMediaKind =
        if (mimeType.startsWith("image/")) LocalMediaKind.Image else LocalMediaKind.Video

    private fun String.extension(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)
}
