package com.apex2060.softphone

/**
 * SDP offer order. pjsua ids look like "PCMU/8000/1", not "PCMU/8000".
 * Priority 0 disables a codec. telephone-event is not in this list; pjmedia
 * adds telephone-event/8000 payload type 120 while an 8 kHz codec is offered.
 */
object CodecPlan {
    const val PCMU = 255
    const val PCMA = 200
    const val TELEPHONE_EVENT = 128

    fun priority(id: String): Int {
        val key = id.lowercase()
        return when {
            key == "pcmu/8000" || key.startsWith("pcmu/8000/") -> PCMU
            key == "pcma/8000" || key.startsWith("pcma/8000/") -> PCMA
            key == "telephone-event/8000" || key.startsWith("telephone-event/8000/") -> TELEPHONE_EVENT
            else -> 0
        }
    }
}
