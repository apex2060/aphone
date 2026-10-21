package com.apex2060.softphone.config

import android.content.Context
import android.content.SharedPreferences
import com.apex2060.softphone.OutboundRoute
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.TransportMode
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
    private const val KEY_OUTBOUND = "outbound_route"
    private const val KEY_MANUAL_USER = "manual_user"
    private const val KEY_MANUAL_PASS = "manual_pass"
    private const val KEY_PHONE = "phone_enabled"
    private const val KEY_AUTO_RECORD = "auto_recording"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow<ProvisionConfig?>(null)
    val config: StateFlow<ProvisionConfig?> = _config
    private val _phoneEnabled = MutableStateFlow(true)
    val phoneEnabled: StateFlow<Boolean> = _phoneEnabled
    private val _autoRecording = MutableStateFlow(false)
    val autoRecording: StateFlow<Boolean> = _autoRecording

    fun init(context: Context) {
        // Same file v1.0.5 used, so an in-place upgrade keeps the QR line.
        prefs = context.getSharedPreferences("softphone", Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null)?.let { SpikeLog.noteSecret(it) }
        prefs.getString(KEY_MANUAL_PASS, null)?.let { SpikeLog.noteSecret(it) }
        _config.value = prefs.getString(KEY_JSON, null)?.let {
            runCatching { ProvisionConfig.parse(it) }.getOrNull()
        }
        _config.value?.sipPassword?.let { SpikeLog.noteSecret(it) }
        _phoneEnabled.value = prefs.getBoolean(KEY_PHONE, true)
        _autoRecording.value = prefs.getBoolean(KEY_AUTO_RECORD, false)
    }

    /** Default on, so an upgrade of a working phone keeps registering. */
    fun setPhoneEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE, on).apply()
        _phoneEnabled.value = on
    }

    /** Hidden. Toggled from the Details title, not from a visible setting. */
    fun setAutoRecording(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, on).apply()
        _autoRecording.value = on
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

    /** Default is the registered phone.plivo.com connection. client.plivo.com is per-call only. */
    fun outboundRoute(): OutboundRoute =
        prefs.getString(KEY_OUTBOUND, null)?.let { name ->
            OutboundRoute.entries.firstOrNull { it.name == name }
        } ?: OutboundRoute.DIRECT

    fun setOutboundRoute(route: OutboundRoute) {
        prefs.edit().putString(KEY_OUTBOUND, route.name).apply()
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
        _autoRecording.value = false
    }

    private fun winKey(signature: String) = "transport_win_$signature"
}
