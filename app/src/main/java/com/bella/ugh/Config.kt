package com.bella.ugh

import android.net.Uri

object Config {

    const val START_URL = "https://ugh.xo.je"

    const val PRIMARY_HOST = "ugh.xo.je"

    val EXTRA_HOSTS: List<String> = emptyList()

    const val INCLUDE_SUBDOMAINS = false

    const val STRIP_WEBVIEW_UA_TOKEN = true

    const val SWIPE_TO_REFRESH = true

    const val OPEN_EXTERNAL_LINKS_IN_CUSTOM_TAB = true

    const val DOUBLE_BACK_TO_EXIT = true

    const val KEEP_SCREEN_ON_WHILE_LOADING = true

    private val hosts: Set<String> =
        (listOf(PRIMARY_HOST) + EXTRA_HOSTS).map { it.lowercase() }.toSet()

    fun isInternal(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            isInternal(Uri.parse(url))
        } catch (e: Exception) {
            false
        }
    }

    fun isInternal(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (hosts.contains(host)) return true
        if (INCLUDE_SUBDOMAINS) {
            return hosts.any { host.endsWith(".$it") }
        }
        return false
    }

    fun desktopUserAgent(defaultUserAgent: String): String {
        val chromeVersion = Regex("Chrome/[0-9]+(?:\\.[0-9]+)*")
            .find(defaultUserAgent)?.value ?: "Chrome/124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "$chromeVersion Safari/537.36"
    }
}
