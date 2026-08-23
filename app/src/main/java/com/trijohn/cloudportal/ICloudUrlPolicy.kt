package com.trijohn.cloudportal

import java.net.URI
import java.util.Locale

/** Restricts top-level WebView navigation to HTTPS pages owned by Apple. */
object ICloudUrlPolicy {
    private val allowedDomains = setOf(
        "icloud.com",
        "icloud.com.cn",
        "apple.com",
        "apple-cloudkit.com",
        "apple-livephotoskit.com",
        "apzones.com",
        "icloud-content.com",
        "cdn-apple.com",
        "apple-dns.net",
    )

    fun isAllowed(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false

        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            allowedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    fun sourceLabel(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return "không xác định"
        if (uri.scheme.equals("blob", ignoreCase = true)) return "blob (dữ liệu tạm của iCloud)"
        return uri.host?.lowercase(Locale.ROOT) ?: uri.scheme?.lowercase(Locale.ROOT) ?: "không xác định"
    }

    /** A blob is trusted only when it was created by the top-level iCloud web app. */
    fun isTrustedBlob(rawUrl: String): Boolean {
        val outer = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!outer.scheme.equals("blob", ignoreCase = true)) return false
        val inner = runCatching { URI(outer.schemeSpecificPart) }.getOrNull() ?: return false
        val host = inner.host?.lowercase(Locale.ROOT) ?: return false
        return inner.scheme.equals("https", ignoreCase = true) &&
            inner.rawUserInfo == null &&
            (inner.port == -1 || inner.port == 443) &&
            host in TRUSTED_BLOB_HOSTS
    }

    private val TRUSTED_BLOB_HOSTS = setOf("www.icloud.com", "www.icloud.com.cn")
}
