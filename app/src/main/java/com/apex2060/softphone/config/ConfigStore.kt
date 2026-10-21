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
