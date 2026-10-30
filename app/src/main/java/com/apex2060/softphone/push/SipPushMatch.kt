package com.apex2060.softphone.push

/**
 * Plivo's dial adds `X-Call-UUID`. Plivo sometimes rewrites custom headers
 * to an `X-PH-` name, so both forms count. Names and values are case-insensitive.
 */
object SipPushMatch {
    fun uuidFromInvite(message: String?): String? {
        if (message.isNullOrBlank()) return null
        for (raw in message.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("SIP/2.0") || line.startsWith("INVITE ", ignoreCase = true)) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            if (!isCallUuidHeader(name)) continue
            val value = line.substring(idx + 1).trim().substringBefore(';').trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    fun isCallUuidHeader(name: String): Boolean {
        if (name.equals("X-Call-UUID", ignoreCase = true)) return true
        if (!name.startsWith("X-PH-", ignoreCase = true)) return false
        return name.contains("Call-UUID", ignoreCase = true)
    }

    /** Header match wins. A missing header still matches the push's caller digits. */
    fun matches(callUuid: String, from: String, headerUuid: String?, inviteFrom: String): Match {
        if (!headerUuid.isNullOrBlank() && headerUuid.equals(callUuid, ignoreCase = true)) return Match.HEADER
        val pushed = digits(from)
        val caller = digits(inviteFrom)
        if (pushed.length >= 7 && caller.isNotEmpty() && (caller.endsWith(pushed) || pushed.endsWith(caller))) {
            return Match.FROM
        }
        return Match.NONE
    }

    fun digits(value: String): String = value.filter { it.isDigit() }

    fun fromUser(message: String?): String {
        if (message.isNullOrBlank()) return ""
        val line = message.lineSequence().firstOrNull {
            it.trim().startsWith("From:", ignoreCase = true)
        } ?: return ""
        val sip = line.substringAfter("sip:", "").substringBefore("@").trim()
        return sip.ifBlank { line.substringAfter(":").substringBefore(";").trim().trim('"') }
    }

    enum class Match { HEADER, FROM, NONE }
}
