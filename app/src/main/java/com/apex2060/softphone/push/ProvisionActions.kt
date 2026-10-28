package com.apex2060.softphone.push

import java.net.URI

/**
 * Rewrites the provision URL's query. `v` and `c` pin an unpublished project version
 * and are always removed. Host and path stay as scanned.
 */
object ProvisionActions {
    fun stripVersionQuery(url: String): String = rewrite(url) { parts ->
        parts.filter { nameOf(it) !in VERSION_PINS }
    }

    fun withAction(url: String, action: String): String = rewrite(url) { parts ->
        parts.filter { name ->
            val n = nameOf(name)
            n != "action" && n !in VERSION_PINS
        } + "action=$action"
    }

    private val VERSION_PINS = setOf("v", "c")

    private fun nameOf(part: String) = part.substringBefore('=').lowercase()

    private fun rewrite(url: String, transform: (List<String>) -> List<String>): String {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return url
        }
        val parts = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        val query = transform(parts).joinToString("&").ifBlank { null }
        return try {
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, query, uri.fragment).toASCIIString()
        } catch (_: Exception) {
            url
        }
    }
}
