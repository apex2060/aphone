package com.twentytwo.phone.sipspike

/**
 * Strips SIP credentials from text before it is shown, shared, or written to logcat.
 * The digest response is not the password, and it still does not belong in a shared log.
 */
object LogSanitizer {
    private val authHeader = Regex("(?i)(Proxy-)?Authorization\\s*:[^\\r\\n]*")

    fun redact(message: String, secret: String?): String {
        var out = authHeader.replace(message) { match ->
            val proxy = if (match.value.startsWith("Proxy", ignoreCase = true) ||
                match.value.startsWith("proxy")
            ) {
                "Proxy-"
            } else {
                ""
            }
            "${proxy}Authorization: [redacted]"
        }
        if (!secret.isNullOrEmpty()) {
            out = out.replace(secret, "[redacted]")
        }
        return out
    }
}
