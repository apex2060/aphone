package com.apex2060.softphone

/**
 * Strips SIP credentials, the provisioning key, and token-like fields before
 * text is shown, shared, written to the log file, or written to logcat.
 */
object LogSanitizer {
    private val authHeader = Regex("(?i)(Proxy-)?Authorization\\s*:[^\\r\\n]*")
    private val provisionHeader = Regex("(?i)(X-Provision-Key|X-Api-Key)\\s*:[^\\r\\n]*")
    private val querySecret = Regex("(?i)([?&](?:key|token|secret|password)=)[^&\\s]*")
    private val jsonSecret = Regex(
        """(?i)"(password|pass|key|apiKey|token|secret|authorization|access_token|refresh_token|fcm|fcmToken|provision_key|provisionKey)"\s*:\s*"(?:\\.|[^"\\])*""""
    )

    fun redact(message: String, secrets: Collection<String?> = emptyList()): String {
        var out = authHeader.replace(message) { match ->
            val proxy = if (match.value.startsWith("Proxy", ignoreCase = true)) "Proxy-" else ""
            "${proxy}Authorization: [redacted]"
        }
        out = provisionHeader.replace(out) { "${it.groupValues[1]}: [redacted]" }
        out = querySecret.replace(out) { "${it.groupValues[1]}[redacted]" }
        out = jsonSecret.replace(out) { "\"${it.groupValues[1]}\":\"[redacted]\"" }
        val literals = secrets
            .filterNot { it.isNullOrEmpty() }
            .map { it!! }
            .distinct()
            .sortedByDescending { it.length }
        for (secret in literals) {
            if (secret.length < 3) continue
            out = out.replace(secret, "[redacted]")
        }
        return out
    }
}
