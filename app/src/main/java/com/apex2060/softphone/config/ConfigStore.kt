package com.apex2060.softphone.config

import android.content.Context
import android.content.SharedPreferences
import com.apex2060.softphone.OutboundRoute
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LineChange { ADDED, UPDATED, FULL }

/**
 * One provisioned phone. Secrets live in the private prefs file and in [rawJson].
 * They are registered with [SpikeLog.noteSecret] and are not written to the log.
 */
data class StoredLine(
    val id: String,
    val rawJson: String,
    val config: ProvisionConfig,
    val ticketUrl: String?,
    val ticketKey: String?,
    val refreshedAt: Long,
    val enabled: Boolean,
    val transportOverride: String?,
    val outboundRoute: String,
    val manualUser: String?,
    val manualPass: String?,
    val transportWins: Map<String, String>,
) {
    val ticket: ProvisionTicket?
        get() = ticketUrl?.takeIf { it.isNotBlank() }?.let { ProvisionTicket(it, ticketKey) }

    fun manualCreds(): Pair<String, String>? {
        val user = manualUser?.trim().orEmpty()
        val pass = manualPass.orEmpty()
        return if (user.isNotEmpty() && pass.isNotEmpty()) user to pass else null
    }

    /** Manual fallback wins when set. Otherwise the provisioned SIP user. */
    fun resolvedCreds(): Pair<String, String>? {
        manualCreds()?.let { return it }
        if (config.sipUsername.isEmpty() || config.sipPassword.isEmpty()) return null
        return config.sipUsername to config.sipPassword
    }

    fun route(): OutboundRoute =
        OutboundRoute.entries.firstOrNull { it.name == outboundRoute } ?: OutboundRoute.DIRECT

    fun transportMode(): TransportMode? =
        transportOverride?.let { name -> TransportMode.entries.firstOrNull { it.name == name } }

    fun winner(signature: String): TransportMode? =
        transportWins[signature]?.let { name -> TransportMode.entries.firstOrNull { it.name == name } }
}

/**
 * Persists every provisioned line. The prefs file is still "softphone", so an
 * upgrade can read the single line v1.0.5 and 1.1.x stored and keep it.
 */
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
    private const val KEY_LINES = "lines_json"
    private const val KEY_LAST_DIAL = "last_dial_line"

    private lateinit var prefs: SharedPreferences
    private val gate = Any()
    private var stored = listOf<StoredLine>()
    private val _lines = MutableStateFlow<List<StoredLine>>(emptyList())
    val lines: StateFlow<List<StoredLine>> = _lines
    private val _lastDial = MutableStateFlow<String?>(null)
    val lastDial: StateFlow<String?> = _lastDial
    private val _autoRecording = MutableStateFlow(false)
    val autoRecording: StateFlow<Boolean> = _autoRecording

    /** Set only during the upgrade that created line 1. History claims those rows. */
    @Volatile
    var pendingHistoryClaim: Pair<String, String>? = null
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("softphone", Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null)?.let { SpikeLog.noteSecret(it) }
        prefs.getString(KEY_MANUAL_PASS, null)?.let { SpikeLog.noteSecret(it) }
        val blob = prefs.getString(KEY_LINES, null)
        val loaded = if (blob != null) {
            decode(blob)
        } else {
            migrateLegacy()
        }
        synchronized(gate) { stored = loaded }
        noteAll(loaded)
        _lines.value = loaded
        _lastDial.value = prefs.getString(KEY_LAST_DIAL, null)
        _autoRecording.value = prefs.getBoolean(KEY_AUTO_RECORD, false)
    }

    fun consumeHistoryClaim(): Pair<String, String>? {
        val claim = pendingHistoryClaim
        pendingHistoryClaim = null
        return claim
    }

    fun line(id: String): StoredLine? = _lines.value.firstOrNull { it.id == id }

    fun anyEnabled(): Boolean = _lines.value.any { it.enabled }

    /** First phone that is on, otherwise the first phone. */
    fun themeLine(): StoredLine? = _lines.value.firstOrNull { it.enabled } ?: _lines.value.firstOrNull()

    /**
     * QR scan. The same SIP username updates that line in place.
     * A new username is appended, up to [LineLogic.MAX_LINES].
     */
    fun upsert(ticket: ProvisionTicket, json: String, config: ProvisionConfig): LineChange {
        synchronized(gate) {
            val index = LineLogic.matchingIndex(stored.map { it.config.sipUsername }, config.sipUsername)
            if (index >= 0) {
                stored = stored.toMutableList().also { list ->
                    list[index] = list[index].withDocument(ticket, json, config)
                }
                persistLocked()
                SpikeLog.append("line updated user=${config.sipUsername}")
                return LineChange.UPDATED
            }
            if (stored.size >= LineLogic.MAX_LINES) return LineChange.FULL
            val created = newLine(ticket, json, config)
            stored = stored + created
            persistLocked()
            SpikeLog.append("line added user=${config.sipUsername}")
            return LineChange.ADDED
        }
    }

    /** Hourly refresh writes back into the line that owns the ticket. */
    fun updateDocument(id: String, ticket: ProvisionTicket, json: String, config: ProvisionConfig): Boolean {
        synchronized(gate) {
            val index = stored.indexOfFirst { it.id == id }
            if (index < 0) return false
            stored = stored.toMutableList().also { list ->
                list[index] = list[index].withDocument(ticket, json, config)
            }
            persistLocked()
            SpikeLog.append("line refreshed user=${config.sipUsername}")
            return true
        }
    }

    fun setEnabled(id: String, on: Boolean) = update(id) { it.copy(enabled = on) }

    fun remove(id: String): Boolean {
        synchronized(gate) {
            if (stored.none { it.id == id }) return false
            stored = stored.filterNot { it.id == id }
            if (_lastDial.value == id) {
                _lastDial.value = null
                prefs.edit().remove(KEY_LAST_DIAL).apply()
            }
            persistLocked()
            SpikeLog.append("line removed")
            return true
        }
    }

    fun setLastDial(id: String) {
        prefs.edit().putString(KEY_LAST_DIAL, id).apply()
        _lastDial.value = id
    }

    fun setOutboundRoute(id: String, route: OutboundRoute) =
        update(id) { it.copy(outboundRoute = route.name) }

    fun setTransportOverride(id: String, mode: TransportMode?) =
        update(id) { it.copy(transportOverride = mode?.name) }

    fun remember(id: String, signature: String, mode: TransportMode) = update(id) {
        it.copy(transportWins = it.transportWins + (signature to mode.name))
    }

    fun forget(id: String, signature: String) = update(id) {
        it.copy(transportWins = it.transportWins - signature)
    }

    fun setManual(id: String, user: String, pass: String) {
        SpikeLog.noteSecret(pass)
        update(id) { it.copy(manualUser = user.trim(), manualPass = pass) }
    }

    fun clearManual(id: String) = update(id) { it.copy(manualUser = null, manualPass = null) }

    /** Hidden. Toggled from the Details title, not from a visible setting. One switch for the app. */
    fun setAutoRecording(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, on).apply()
        _autoRecording.value = on
    }

    var askedBattery: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_BATTERY, value).apply()

    var askedFullScreen: Boolean
        get() = prefs.getBoolean(KEY_ASKED_FULL_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_FULL_SCREEN, value).apply()

    fun clear() {
        synchronized(gate) {
            stored = emptyList()
            prefs.edit().clear().apply()
            _lines.value = emptyList()
            _lastDial.value = null
            _autoRecording.value = false
        }
    }

    private fun update(id: String, block: (StoredLine) -> StoredLine) {
        synchronized(gate) {
            val index = stored.indexOfFirst { it.id == id }
            if (index < 0) return
            stored = stored.toMutableList().also { it[index] = block(it[index]) }
            persistLocked()
        }
    }

    private fun persistLocked() {
        prefs.edit().putString(KEY_LINES, encode(stored)).apply()
        _lines.value = stored
    }

    private fun noteAll(lines: List<StoredLine>) {
        for (line in lines) {
            SpikeLog.noteSecret(line.config.sipPassword)
            line.ticketKey?.let { SpikeLog.noteSecret(it) }
            line.manualPass?.let { SpikeLog.noteSecret(it) }
        }
    }

    private fun StoredLine.withDocument(
        ticket: ProvisionTicket,
        json: String,
        config: ProvisionConfig,
    ): StoredLine {
        SpikeLog.noteSecret(ticket.key)
        SpikeLog.noteSecret(config.sipPassword)
        return copy(
            rawJson = json,
            config = config,
            ticketUrl = ticket.url,
            ticketKey = ticket.key,
            refreshedAt = System.currentTimeMillis(),
        )
    }

    private fun newLine(ticket: ProvisionTicket, json: String, config: ProvisionConfig) =
        StoredLine(
            id = UUID.randomUUID().toString(),
            rawJson = json,
            config = config,
            ticketUrl = ticket.url,
            ticketKey = ticket.key,
            refreshedAt = System.currentTimeMillis(),
            enabled = true,
            transportOverride = null,
            outboundRoute = OutboundRoute.DIRECT.name,
            manualUser = null,
            manualPass = null,
            transportWins = emptyMap(),
        ).also {
            SpikeLog.noteSecret(ticket.key)
            SpikeLog.noteSecret(config.sipPassword)
        }

    /**
     * Copies the single-line prefs into line 1. The old keys stay in the file
     * so a backup of this build can still see them. Runtime reads [KEY_LINES].
     */
    private fun migrateLegacy(): List<StoredLine> {
        val json = prefs.getString(KEY_JSON, null) ?: return emptyList()
        val config = runCatching { ProvisionConfig.parse(json) }.getOrNull() ?: run {
            SpikeLog.append("migration skipped, provisioning document unusable")
            return emptyList()
        }
        val line = StoredLine(
            id = UUID.randomUUID().toString(),
            rawJson = json,
            config = config,
            ticketUrl = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() },
            ticketKey = prefs.getString(KEY_SECRET, null),
            refreshedAt = prefs.getLong(KEY_REFRESHED, 0),
            enabled = prefs.getBoolean(KEY_PHONE, true),
            transportOverride = prefs.getString(KEY_OVERRIDE, null)?.takeIf { it.isNotBlank() },
            outboundRoute = prefs.getString(KEY_OUTBOUND, null)?.takeIf { it.isNotBlank() }
                ?: OutboundRoute.DIRECT.name,
            manualUser = prefs.getString(KEY_MANUAL_USER, null)?.takeIf { it.isNotBlank() },
            manualPass = prefs.getString(KEY_MANUAL_PASS, null)?.takeIf { it.isNotEmpty() },
            transportWins = LineLogic.legacyWins(prefs.all),
        )
        prefs.edit().putString(KEY_LINES, encode(listOf(line))).apply()
        pendingHistoryClaim = line.id to line.config.title
        SpikeLog.append("migrated existing phone to line 1 user=${config.sipUsername}")
        return listOf(line)
    }

    private fun encode(lines: List<StoredLine>): String {
        val arr = JSONArray()
        for (line in lines) {
            val wins = JSONObject()
            line.transportWins.forEach { (signature, mode) -> wins.put(signature, mode) }
            arr.put(
                JSONObject()
                    .put("id", line.id)
                    .put("json", line.rawJson)
                    .put("url", line.ticketUrl.orEmpty())
                    .put("key", line.ticketKey.orEmpty())
                    .put("refreshedAt", line.refreshedAt)
                    .put("enabled", line.enabled)
                    .put("transportOverride", line.transportOverride.orEmpty())
                    .put("outboundRoute", line.outboundRoute)
                    .put("manualUser", line.manualUser.orEmpty())
                    .put("manualPass", line.manualPass.orEmpty())
                    .put("wins", wins),
            )
        }
        return JSONObject().put("lines", arr).toString()
    }

    private fun decode(blob: String): List<StoredLine> {
        val root = runCatching { JSONObject(blob) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("lines") ?: return emptyList()
        val out = ArrayList<StoredLine>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val json = o.optString("json")
            val config = runCatching { ProvisionConfig.parse(json) }.getOrNull()
            if (config == null) {
                SpikeLog.append("line skipped, provisioning document unusable")
                continue
            }
            val winsObj = o.optJSONObject("wins")
            val wins = LinkedHashMap<String, String>()
            if (winsObj != null) {
                val keys = winsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mode = winsObj.optString(key)
                    if (key.isNotEmpty() && mode.isNotEmpty()) wins[key] = mode
                }
            }
            out += StoredLine(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                rawJson = json,
                config = config,
                ticketUrl = o.optString("url").ifBlank { null },
                ticketKey = o.optString("key").ifBlank { null },
                refreshedAt = o.optLong("refreshedAt"),
                enabled = o.optBoolean("enabled", true),
                transportOverride = o.optString("transportOverride").ifBlank { null },
                outboundRoute = o.optString("outboundRoute").ifBlank { OutboundRoute.DIRECT.name },
                manualUser = o.optString("manualUser").ifBlank { null },
                manualPass = o.optString("manualPass").ifBlank { null },
                transportWins = wins,
            )
        }
        return out
    }
}
