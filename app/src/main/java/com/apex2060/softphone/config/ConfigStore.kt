package com.apex2060.softphone.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persists the provisioning document plus where it came from, so the phone survives restarts. */
object ConfigStore {
    private const val KEY_JSON = "config_json"
    private const val KEY_URL = "provision_url"
    private const val KEY_SECRET = "provision_key"
    private const val KEY_REFRESHED = "refreshed_at"
    private const val KEY_ASKED_BATTERY = "asked_battery"
    private const val KEY_ASKED_FULL_SCREEN = "asked_full_screen"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_STAY_REGISTERED = "stay_registered"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow<ProvisionConfig?>(null)
    val config: StateFlow<ProvisionConfig?> = _config

    fun init(context: Context) {
        prefs = context.getSharedPreferences("softphone", Context.MODE_PRIVATE)
        _config.value = prefs.getString(KEY_JSON, null)?.let {
            runCatching { ProvisionConfig.parse(it) }.getOrNull()
        }
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

    /** Last FCM token. Not the SIP password. Cleared with the rest of the phone on reset. */
    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    /** Debug aid: keep a SIP registration instead of waiting for push. Default off. */
    var stayRegistered: Boolean
        get() = prefs.getBoolean(KEY_STAY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_STAY_REGISTERED, value).apply()

    fun save(ticket: ProvisionTicket, json: String, config: ProvisionConfig) {
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
}
