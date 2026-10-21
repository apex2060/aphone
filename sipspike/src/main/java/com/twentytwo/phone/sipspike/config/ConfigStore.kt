package com.twentytwo.phone.sipspike.config

import android.content.Context
import android.content.SharedPreferences
import com.twentytwo.phone.sipspike.SpikeLog
import com.twentytwo.phone.sipspike.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persists the provisioning document plus where it came from. Private to this app. */
object ConfigStore {
    private const val KEY_JSON = "config_json"
    private const val KEY_URL = "provision_url"
    private const val KEY_SECRET = "provision_key"
    private const val KEY_REFRESHED = "refreshed_at"
    private const val KEY_ASKED_BATTERY = "asked_battery"
    private const val KEY_ASKED_FULL_SCREEN = "asked_full_screen"
    private const val KEY_OVERRIDE = "transport_override"
    private const val KEY_MANUAL_USER = "manual_user"
    private const val KEY_MANUAL_PASS = "manual_pass"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow<ProvisionConfig?>(null)
    val config: StateFlow<ProvisionConfig?> = _config

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sipspike", Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null)?.let { SpikeLog.noteSecret(it) }
        prefs.getString(KEY_MANUAL_PASS, null)?.let { SpikeLog.noteSecret(it) }
        _config.value = prefs.getString(KEY_JSON, null)?.let {
            runCatching { ProvisionConfig.parse(it) }.getOrNull()
        }
        _config.value?.sipPassword?.let { SpikeLog.noteSecret(it) }
    }

    val ticket: ProvisionTicket?
        get() = prefs.getString(KEY_URL, null)?.let { ProvisionTicket(it, prefs.getString(KEY_SECRET, null)) }

    val refreshedAt: Long get() = prefs.getLong(KEY_REFRESHED, 0)

    var askedBattery: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_BATTERY, value).apply()

    var askedFullScreen: Boolean
        get() = prefs.getBoolean(KEY_ASKED_FULL_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_FULL_SCREEN, value).apply()

    /** Manual SIP login is the admin fallback. Empty means use the provisioned line. */
    fun manualCreds(): Pair<String, String>? {
        val user = prefs.getString(KEY_MANUAL_USER, null)?.trim().orEmpty()
        val pass = prefs.getString(KEY_MANUAL_PASS, null).orEmpty()
        return if (user.isNotEmpty() && pass.isNotEmpty()) user to pass else null
    }

    fun setManual(user: String, pass: String) {
        SpikeLog.noteSecret(pass)
        prefs.edit().putString(KEY_MANUAL_USER, user.trim()).putString(KEY_MANUAL_PASS, pass).apply()
    }

    fun clearManual() {
        prefs.edit().remove(KEY_MANUAL_USER).remove(KEY_MANUAL_PASS).apply()
    }

    /** Credentials the stack should register with. Manual fallback wins when set. */
    fun resolvedCreds(): Pair<String, String>? {
        manualCreds()?.let { return it }
        val cfg = _config.value ?: return null
        return cfg.sipUsername to cfg.sipPassword
    }

    fun transportOverride(): TransportMode? =
        prefs.getString(KEY_OVERRIDE, null)?.let { name ->
            TransportMode.entries.firstOrNull { it.name == name }
        }

    fun setTransportOverride(mode: TransportMode?) {
        prefs.edit().apply {
            if (mode == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, mode.name)
        }.apply()
    }

    fun winner(signature: String): TransportMode? =
        prefs.getString(winKey(signature), null)?.let { name ->
            TransportMode.entries.firstOrNull { it.name == name }
        }

    fun remember(signature: String, mode: TransportMode) {
        prefs.edit().putString(winKey(signature), mode.name).apply()
    }

    fun forget(signature: String) {
        prefs.edit().remove(winKey(signature)).apply()
    }

    fun save(ticket: ProvisionTicket, json: String, config: ProvisionConfig) {
        SpikeLog.noteSecret(ticket.key)
        SpikeLog.noteSecret(config.sipPassword)
        prefs.edit()
            .putString(KEY_JSON, json)
            .putString(KEY_URL, ticket.url)
            .putString(KEY_SECRET, ticket.key)
            .putLong(KEY_REFRESHED, System.currentTimeMillis())
            .apply()
        _config.value = config
    }

    fun clear() {
        prefs.edit().clear().apply()
        _config.value = null
    }

    private fun winKey(signature: String) = "transport_win_$signature"
}
