package com.apex2060.softphone.push

import com.apex2060.softphone.config.ConfigStore

/** Push is active for a line after that line's own origin accepted its token. */
object PushGate {
    fun parked(lineId: String): Boolean = ConfigStore.isPushAcked(lineId)

    /** Idle always-on registration is only for lines the server cannot wake. */
    fun needsIdleService(): Boolean =
        ConfigStore.lines.value.any { it.enabled && !parked(it.id) }
}
