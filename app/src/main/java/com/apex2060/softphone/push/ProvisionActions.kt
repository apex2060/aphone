package com.apex2060.softphone.push

import java.net.URI

/** Rewrites the `action` query on the line's provision URL and leaves the rest alone. */
object ProvisionActions {
    fun withAction(url: String, action: String): String {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return url
        }
        val kept = uri.rawQuery.orEmpty()
            .split('&')
            .filter { part ->
                part.isNotEmpty() && !part.substringBefore('=').equals("action", ignoreCase = true)
            }
        val query = (kept + "action=$action").joinToString("&")
        return try {
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, query, uri.fragment).toASCIIString()
        } catch (_: Exception) {
            url
        }
    }
}
