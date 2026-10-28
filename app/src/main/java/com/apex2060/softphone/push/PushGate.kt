package com.apex2060.softphone.push

import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.config.ConfigStore

/** Push is "active" for a line after that line's token upload returned HTTP 200. */
object PushGate {
    fun compiled(): Boolean = BuildConfig.PUSH_ENABLED

    fun parked(lineId: String): Boolean = compiled() && ConfigStore.isPushAcked(lineId)

    /** Idle always-on registration is only for lines the server cannot wake. */
    fun needsIdleService(): Boolean =
        ConfigStore.lines.value.any { it.enabled && !parked(it.id) }
}
