package com.apex2060.softphone.phone

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.Direction
import com.apex2060.softphone.history.Outcome
import com.plivo.endpoint.Endpoint
import com.plivo.endpoint.EventListener
import com.plivo.endpoint.Incoming
import com.plivo.endpoint.Outgoing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Registration { OFFLINE, CONNECTING, ONLINE, FAILED }

enum class CallPhase { RINGING_IN, DIALING, RINGING_OUT, ACTIVE, ENDED }

data class CallState(
    val direction: Direction,
    val number: String,
    val phase: CallPhase,
    val startedAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val endedLabel: String? = null,
    val outcome: Outcome? = null,
)

/**
 * Owns the single Plivo [Endpoint] and turns its callbacks into observable state.
 * Plivo calls back on its own threads; everything here is either a StateFlow or posted to main.
 */
object PhoneManager : EventListener {
    private const val TAG = "PhoneManager"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private val _registration = MutableStateFlow(Registration.OFFLINE)
    val registration: StateFlow<Registration> = _registration

    private val _call = MutableStateFlow<CallState?>(null)
    val call: StateFlow<CallState?> = _call

    private var endpoint: Endpoint? = null
    private var incoming: Incoming? = null
    private var outgoing: Outgoing? = null
    private var userDeclined = false

    private var creds: Pair<String, String>? = null
    private var pendingCreds: Pair<String, String>? = null
    private var wantOnline = false
    private var retryDelayMs = RETRY_MIN_MS

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ---- registration -------------------------------------------------------------------------

    /** Idempotent: safe to call whenever the config is (re)loaded. Must be called on main. */
    fun login(username: String, password: String) {
        wantOnline = true
        val next = username to password
        val ep = endpoint ?: Endpoint.newInstance(app, BuildConfig.DEBUG, this).also { endpoint = it }
        if (creds == next && _registration.value in setOf(Registration.ONLINE, Registration.CONNECTING)) return
        if (creds != null && creds != next && ep.registered) {
            // Credentials rotated: log out first, onLogout() logs back in with the new pair.
            pendingCreds = next
            ep.logout()
            return
        }
        creds = next
        _registration.value = Registration.CONNECTING
        if (!ep.login(username, password)) scheduleRetry()
    }

    fun logout() {
        wantOnline = false
        pendingCreds = null
        main.removeCallbacks(retry)
        endpoint?.let { if (it.registered) it.logout() }
        creds = null
        _registration.value = Registration.OFFLINE
    }

    private val retry = Runnable {
        val c = creds ?: return@Runnable
        if (wantOnline && _registration.value != Registration.ONLINE) {
            _registration.value = Registration.CONNECTING
            if (endpoint?.login(c.first, c.second) != true) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        _registration.value = Registration.FAILED
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    override fun onLogin() {
        Log.i(TAG, "registered")
        retryDelayMs = RETRY_MIN_MS
        _registration.value = Registration.ONLINE
    }

    override fun onLogout() {
        Log.i(TAG, "logged out")
        _registration.value = Registration.OFFLINE
        val next = pendingCreds ?: return
        pendingCreds = null
        creds = null
        main.post { login(next.first, next.second) }
    }

    override fun onLoginFailed() = onLoginFailed("unknown")

    override fun onLoginFailed(reason: String?) {
        Log.w(TAG, "login failed: $reason")
        main.post { if (wantOnline) scheduleRetry() }
    }

    // ---- calls --------------------------------------------------------------------------------

    /** Strips formatting and applies the provisioned country code to bare national numbers. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("sip:")) return trimmed
        val digits = trimmed.filter { it.isDigit() || it == '*' || it == '#' }
        val cc = ConfigStore.config.value?.countryCode
        return when {
            trimmed.startsWith("+") -> digits
            cc == "1" && digits.length == 10 -> "1$digits"
            else -> digits
        }
    }

    fun dial(raw: String): Boolean {
        val ep = endpoint ?: return false
        if (_registration.value != Registration.ONLINE || _call.value != null) return false
        val number = normalize(raw)
        if (number.isEmpty()) return false
        val out = ep.createOutgoingCall() ?: return false
        outgoing = out
        _call.value = CallState(Direction.OUTGOING, number, CallPhase.DIALING)
        if (!out.call(number)) {
            finish(Outcome.FAILED, "Call failed")
            return false
        }
        return true
    }

    fun answer() {
        val inc = incoming ?: return
        if (_call.value?.phase != CallPhase.RINGING_IN) return
        inc.answer()
        markAnswered()
    }

    fun decline() {
        val inc = incoming ?: return
        userDeclined = true
        inc.reject()
        finish(Outcome.DECLINED, "Declined")
    }

    fun hangup() {
        val state = _call.value ?: return
        when {
            state.phase == CallPhase.RINGING_IN -> decline()
            outgoing != null -> outgoing?.hangup()
            else -> incoming?.hangup()
        }
        // Some failure paths never call back; make sure the UI is released.
        main.postDelayed({
            if (_call.value != null && _call.value?.phase != CallPhase.ENDED && _call.value?.startedAt == state.startedAt) {
                finish(if (state.answeredAt != null) Outcome.COMPLETED else Outcome.FAILED, "Call ended")
            }
        }, 4000)
    }

    fun toggleMute() {
        val state = _call.value ?: return
        val mute = !state.muted
        if (mute) {
            outgoing?.mute(); incoming?.mute()
        } else {
            outgoing?.unmute(); incoming?.unmute()
        }
        _call.update { it?.copy(muted = mute) }
    }

    fun toggleSpeaker() {
        val on = !(_call.value?.speaker ?: return)
        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
        _call.update { it?.copy(speaker = on) }
    }

    fun sendDigit(digit: Char) {
        val d = digit.toString()
        outgoing?.sendDigits(d) ?: incoming?.sendDigits(d)
    }

    private fun markAnswered() {
        _call.update { it?.copy(phase = CallPhase.ACTIVE, answeredAt = it.answeredAt ?: System.currentTimeMillis()) }
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
    }

    @Synchronized
    private fun finish(outcome: Outcome, label: String) {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        val duration = state.answeredAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
        CallHistory.add(
            number = state.number,
            direction = state.direction,
            outcome = outcome,
            startedAt = state.startedAt,
            durationSec = duration,
            limit = ConfigStore.config.value?.historyLimit ?: 200,
        )
        incoming = null
        outgoing = null
        userDeclined = false
        _call.value = state.copy(phase = CallPhase.ENDED, endedLabel = label, outcome = outcome)

        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL

        // Leave "Call ended" on screen briefly, then release the call UI.
        main.postDelayed({ if (_call.value?.startedAt == state.startedAt) _call.value = null }, 1500)
    }

    // ---- Plivo callbacks: incoming ------------------------------------------------------------

    override fun onIncomingCall(inc: Incoming) {
        val from = callerOf(inc)
        if (_call.value != null && _call.value?.phase != CallPhase.ENDED) {
            // Single-line phone: busy. Log it as missed so the user can call back.
            inc.reject()
            CallHistory.add(from, Direction.INCOMING, Outcome.MISSED, System.currentTimeMillis(), 0,
                ConfigStore.config.value?.historyLimit ?: 200)
            return
        }
        incoming = inc
        userDeclined = false
        _call.value = CallState(Direction.INCOMING, from, CallPhase.RINGING_IN)
    }

    override fun onIncomingCallConnected(inc: Incoming) = markAnswered()

    override fun onIncomingCallHangup(inc: Incoming) {
        val answered = _call.value?.answeredAt != null
        finish(if (answered) Outcome.COMPLETED else Outcome.MISSED, if (answered) "Call ended" else "Missed call")
    }

    override fun onIncomingCallRejected(inc: Incoming) {
        if (userDeclined) finish(Outcome.DECLINED, "Declined") else finish(Outcome.MISSED, "Missed call")
    }

    override fun onIncomingCallInvalid(inc: Incoming) = finish(Outcome.MISSED, "Missed call")

    private fun callerOf(inc: Incoming): String {
        val raw = inc.fromContact?.takeIf { it.isNotBlank() } ?: inc.fromSip.orEmpty()
        // "sip:15551234567@phone.plivo.com" -> "15551234567"
        return raw.removePrefix("sip:").substringBefore('@').ifBlank { "Unknown" }
    }

    // ---- Plivo callbacks: outgoing ------------------------------------------------------------

    override fun onOutgoingCall(out: Outgoing) = Unit

    override fun onOutgoingCallRinging(out: Outgoing) {
        _call.update { if (it != null && it.phase == CallPhase.DIALING) it.copy(phase = CallPhase.RINGING_OUT) else it }
    }

    override fun onOutgoingCallAnswered(out: Outgoing) = markAnswered()

    override fun onOutgoingCallRejected(out: Outgoing) = finish(Outcome.FAILED, "Call not answered")

    override fun onOutgoingCallHangup(out: Outgoing) {
        val answered = _call.value?.answeredAt != null
        finish(if (answered) Outcome.COMPLETED else Outcome.FAILED, if (answered) "Call ended" else "Call not answered")
    }

    override fun onOutgoingCallInvalid(out: Outgoing) = finish(Outcome.FAILED, "Invalid number")

    // ---- Plivo callbacks: misc ----------------------------------------------------------------

    override fun mediaMetrics(metrics: HashMap<String, Any>?) = Unit

    override fun onPermissionDenied(message: String?) {
        Log.w(TAG, "permission denied: $message")
    }

    override fun audioDeviceChange(event: String?, device: AudioDeviceInfo?) = Unit

    override fun speakingOnMute() = Unit

    private const val RETRY_MIN_MS = 5_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
}
