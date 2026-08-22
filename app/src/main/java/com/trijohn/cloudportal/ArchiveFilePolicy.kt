package com.trijohn.cloudportal

import java.net.URLConnection
import java.util.Locale

internal object ArchiveFilePolicy {
    private val invalidFileCharacters = Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]")

    fun isZip(fileName: String, mimeType: String?): Boolean =
        fileName.endsWith(".zip", ignoreCase = true) ||
            mimeType.equals("application/zip", ignoreCase = true) ||
            mimeType.equals("application/x-zip-compressed", ignoreCase = true)

    fun sanitizeFileName(name: String): String = name
        .replace(invalidFileCharacters, "_")
        .trim()
        .trimEnd('.')

    fun safeEntryFileName(entryName: String): String {
        val leafName = entryName.substringAfterLast('/').substringAfterLast('\\')
        return sanitizeFileName(leafName)
            .take(160)
            .ifBlank { "icloud-file" }
    }

    fun archiveBaseName(fileName: String): String {
        val withoutExtension = if (fileName.endsWith(".zip", ignoreCase = true)) {
            fileName.dropLast(4)
        } else {
            fileName
        }
        return sanitizeFileName(withoutExtension)
            .take(80)
            .ifBlank { "iCloud Photos" }
    }

    fun uniqueFileName(fileName: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(fileName.lowercase(Locale.ROOT))) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var number = 2
        while (true) {
            val candidate = "${stem}_$number$extension"
            if (usedNames.add(candidate.lowercase(Locale.ROOT))) return candidate
            number++
        }
    }

    fun mimeType(fileName: String): String =
        URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
}
