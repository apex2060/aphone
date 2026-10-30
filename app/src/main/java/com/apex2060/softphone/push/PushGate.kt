package com.apex2060.softphone.push

import com.apex2060.softphone.config.ConfigStore

/** Push is active for a line after that line's own origin accepted its token. */
object PushGate {
    fun parked(lineId: String): Boolean = ConfigStore.isPushAcked(lineId)
}
