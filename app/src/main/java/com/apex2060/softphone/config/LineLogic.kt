package com.apex2060.softphone.config

/** Pure helpers for the line list. No Android APIs, so unit tests can call them. */
object LineLogic {
    const val MAX_LINES = 8
    const val WIN_PREFIX = "transport_win_"

    fun sameUsername(a: String, b: String): Boolean = a.trim() == b.trim()

    fun matchingIndex(usernames: List<String>, username: String): Int =
        usernames.indexOfFirst { sameUsername(it, username) }

    /** Keys left by the single-line build, `transport_win_<signature>` → mode name. */
    fun legacyWins(entries: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in entries) {
            if (!key.startsWith(WIN_PREFIX)) continue
            val mode = value as? String ?: continue
            if (mode.isEmpty()) continue
            val signature = key.removePrefix(WIN_PREFIX)
            if (signature.isEmpty()) continue
            out[signature] = mode
        }
        return out
    }
}
