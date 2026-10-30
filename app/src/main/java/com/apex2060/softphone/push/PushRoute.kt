package com.apex2060.softphone.push

import java.net.URI

/** One enabled line, reduced to the fields a push can be matched against. */
data class PushLine(
    val id: String,
    val username: String,
    val ticketUrl: String?,
    val subtitle: String?,
)

/**
 * Prefer `endpoint` (SIP username) and `origin` (provision host).
 * When both are absent, any enabled line qualifies, narrowed by `to` when that matches a number.
 */
object PushRoute {
    /**
     * FCM v1 rejects a data key named `from`. Servers send `caller`.
     * `from` is only a legacy fallback.
     */
    fun callerDigits(data: Map<String, String>): String {
        val caller = data["caller"]?.trim().orEmpty()
        if (caller.isNotEmpty()) return caller
        return data["from"]?.trim().orEmpty()
    }

    fun select(lines: List<PushLine>, endpoint: String?, origin: String?, to: String?): Set<String> {
        if (lines.isEmpty()) return emptySet()
        val user = endpoint?.trim().orEmpty()
        val host = origin?.trim().orEmpty()
        if (user.isEmpty() && host.isEmpty()) return fallback(lines, to.orEmpty())
        return lines.filter { line ->
            val userOk = user.isEmpty() || line.username.equals(user, ignoreCase = true)
            val originOk = host.isEmpty() || sameOrigin(line.ticketUrl, host)
            userOk && originOk
        }.map { it.id }.toSet()
    }

    fun sameOrigin(ticketUrl: String?, origin: String): Boolean {
        val ticketHost = hostOf(ticketUrl) ?: return false
        val originHost = hostOf(origin) ?: hostOf("https://$origin") ?: return false
        return ticketHost.equals(originHost, ignoreCase = true)
    }

    private fun fallback(lines: List<PushLine>, to: String): Set<String> {
        val digits = SipPushMatch.digits(to)
        if (digits.isEmpty()) return lines.map { it.id }.toSet()
        val hits = lines.filter { line ->
            val user = SipPushMatch.digits(line.username)
            val sub = SipPushMatch.digits(line.subtitle.orEmpty())
            (user.isNotEmpty() && (user.endsWith(digits) || digits.endsWith(user))) ||
                (sub.length >= 7 && (sub.contains(digits) || digits.contains(sub)))
        }
        return (if (hits.isEmpty()) lines else hits).map { it.id }.toSet()
    }

    private fun hostOf(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return try {
            URI(url).host?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
