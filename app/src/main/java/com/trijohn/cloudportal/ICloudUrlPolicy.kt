package com.trijohn.cloudportal

import java.net.URI
import java.util.Locale

/** Restricts top-level WebView navigation to HTTPS pages owned by Apple. */
object ICloudUrlPolicy {
    private val allowedDomains = setOf(
        "icloud.com",
        "apple.com",
        "apple-cloudkit.com",
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
}
