package com.apex2060.softphone.push

import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.config.ConfigStore

/** Push is active for a line after that line's own origin accepted its token. */
object PushGate {
    fun parked(lineId: String): Boolean = ConfigStore.isPushAcked(lineId)

    /** Idle always-on registration is only for lines the server cannot wake. */
    fun needsIdleService(): Boolean =
        ConfigStore.lines.value.any { it.enabled && !parked(it.id) }

    /**
     * Skip the first-run battery prompt when every switched-on line can be woken:
     * it named a Firebase project, or this build has the default google-services app.
     */
    fun suppressBatteryPrompt(): Boolean {
        val enabled = ConfigStore.lines.value.filter { it.enabled }
        if (enabled.isEmpty()) return false
        return enabled.all { it.config.push != null || BuildConfig.PUSH_ENABLED }
    }
}
