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
import com.plivo.endpoint.SipController
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
 *
 * A normal remote BYE is reported to Java as call-state status 200 and does not call
 * [onOutgoingCallHangup], and it leaves [Outgoing] active. [Endpoint.isCallRunning] stays true,
 * the in-call UI stays up, and the SDK rejects the next INVITE with 486 while that flag is set.
 */
object PhoneManager : EventListener {
    private const val TAG = "PhoneManager"
    private const val ENDED_HOLD_MS = 400L
    private const val WATCHDOG_MS = 1_000L

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
    private var callHookInstalled = false
    private var sawSignalling = false

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
        val ep = endpoint ?: Endpoint.newInstance(app, BuildConfig.DEBUG, this).also {
            endpoint = it
            installCallHook(it)
        }
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
        main.removeCallbacks(watchdog)
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
        Log.i(TAG, "registered sdkCallRunning=${sdkCallRunning()}")
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
        if (_registration.value != Registration.ONLINE || activeCall() != null) return false
        val number = normalize(raw)
        if (number.isEmpty()) return false
        val out = ep.createOutgoingCall() ?: return false
        outgoing = out
        sawSignalling = false
        _call.value = CallState(Direction.OUTGOING, number, CallPhase.DIALING)
        armWatchdog()
        Log.i(TAG, "dial $number sdkCallRunning=${sdkCallRunning()}")
        if (!out.call(number)) {
            Log.w(TAG, "dial rejected by SDK for $number")
            finish(Outcome.FAILED, "Call failed")
            return false
        }
        return true
    }

    fun answer() {
        val inc = incoming ?: run {
            Log.w(TAG, "answer with no Incoming phase=${_call.value?.phase}")
            return
        }
        if (_call.value?.phase != CallPhase.RINGING_IN) return
        inc.answer()
        markAnswered()
    }

    fun decline() {
        val state = _call.value ?: return
        if (state.phase != CallPhase.RINGING_IN) return
        userDeclined = true
        Log.i(TAG, "decline incoming from=${state.number}")
        runCatching { incoming?.reject() }
            .onFailure { Log.w(TAG, "reject failed: ${it.javaClass.simpleName}") }
        finish(Outcome.DECLINED, "Declined")
    }

    fun hangup() {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        if (state.phase == CallPhase.RINGING_IN) {
            decline()
            return
        }
        Log.i(TAG, "local hangup phase=${state.phase} direction=${state.direction} sdkCallRunning=${sdkCallRunning()}")
        val sent = runCatching { outgoing?.hangup() == true }.getOrDefault(false) ||
            runCatching { incoming?.hangup() == true }.getOrDefault(false)
        if (!sent) {
            runCatching { controller()?.hangup() }
                .onFailure { Log.w(TAG, "controller hangup failed: ${it.javaClass.simpleName}") }
        }
        val answered = state.answeredAt != null
        finish(if (answered) Outcome.COMPLETED else Outcome.FAILED, "Call ended")
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

    /**
     * Records history, drops in-call audio, and clears both the UI call and the SDK's
     * "call running" flag. Idempotent: a late callback cannot resurrect the call or log it twice.
     */
    @Synchronized
    private fun finish(outcome: Outcome, label: String) {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        // Publish ENDED before releasing the SDK. hangup/reset can re-enter this method.
        _call.value = state.copy(phase = CallPhase.ENDED, endedLabel = label, outcome = outcome)
        val runningBefore = sdkCallRunning()
        val duration = state.answeredAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
        CallHistory.add(
            number = state.number,
            direction = state.direction,
            outcome = outcome,
            startedAt = state.startedAt,
            durationSec = duration,
            limit = ConfigStore.config.value?.historyLimit ?: 200,
        )
        releaseSdkCall()
        val runningAfter = sdkCallRunning()
        Log.i(
            TAG,
            "call teardown outcome=$outcome phase=${state.phase} direction=${state.direction} " +
                "sdkCallRunning=$runningBefore->$runningAfter",
        )
        if (runningAfter) Log.w(TAG, "SDK still reports a call running after teardown")
        incoming = null
        outgoing = null
        userDeclined = false
        sawSignalling = false
        main.removeCallbacks(watchdog)

        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL

        // Hold ENDED only long enough for the missed-call notification to observe it.
        main.postDelayed({ if (_call.value?.startedAt == state.startedAt) _call.value = null }, ENDED_HOLD_MS)
    }

    /** A call the UI still considers in progress. The brief ENDED hold does not count. */
    private fun activeCall(): CallState? = _call.value?.takeIf { it.phase != CallPhase.ENDED }

    private fun sdkCallRunning(): Boolean = endpoint?.isCallRunning() == true

    // ---- Plivo callbacks: incoming ------------------------------------------------------------

    override fun onIncomingCall(inc: Incoming?) {
        val phase = _call.value?.phase
        val running = sdkCallRunning()
        if (inc == null) {
            // A SIP INVITE reaches this callback with a null Incoming. localPhase / sdkCallRunning
            // say whether a leftover call would make the stack reject it busy (486).
            Log.w(TAG, "onIncomingCall null Incoming localPhase=${phase ?: "none"} sdkCallRunning=$running")
            return
        }
        val from = callerOf(inc)
        if (activeCall() != null) {
            Log.w(TAG, "incoming refused, line busy from=$from localPhase=$phase sdkCallRunning=$running")
            inc.reject()
            CallHistory.add(
                from, Direction.INCOMING, Outcome.MISSED, System.currentTimeMillis(), 0,
                ConfigStore.config.value?.historyLimit ?: 200,
            )
            return
        }
        Log.i(TAG, "incoming from=$from sdkCallRunning=$running")
        incoming = inc
        userDeclined = false
        sawSignalling = false
        _call.value = CallState(Direction.INCOMING, from, CallPhase.RINGING_IN)
        armWatchdog()
    }

    override fun onIncomingCallConnected(inc: Incoming?) = markAnswered()

    override fun onIncomingCallHangup(inc: Incoming?) {
        if (_call.value?.direction != Direction.INCOMING) {
            Log.i(TAG, "incoming hangup ignored, on-screen call is ${_call.value?.direction} ${_call.value?.phase}")
            return
        }
        Log.i(TAG, "incoming hangup")
        onRemoteEnd()
    }

    override fun onIncomingCallRejected(inc: Incoming?) {
        val state = _call.value
        if (state != null && state.phase != CallPhase.ENDED && state.direction != Direction.INCOMING && !userDeclined) {
            // A busy reject for a second invite must not tear down the call already on screen.
            Log.i(TAG, "incoming rejected while ${state.direction} ${state.phase} is up; leaving that call")
            return
        }
        Log.i(TAG, "incoming rejected local=$userDeclined")
        if (userDeclined) finish(Outcome.DECLINED, "Declined") else finish(Outcome.MISSED, "Missed call")
    }

    override fun onIncomingCallInvalid(inc: Incoming?) {
        if (_call.value?.direction != Direction.INCOMING) {
            Log.i(TAG, "incoming invalid ignored, on-screen call is ${_call.value?.direction}")
            return
        }
        Log.i(TAG, "incoming invalid")
        finish(Outcome.MISSED, "Missed call")
    }

    private fun callerOf(inc: Incoming): String {
        val raw = inc.fromContact?.takeIf { it.isNotBlank() } ?: inc.fromSip.orEmpty()
        return sipUser(raw) ?: "Unknown"
    }

    // ---- Plivo callbacks: outgoing ------------------------------------------------------------

    override fun onOutgoingCall(out: Outgoing?) = Unit

    override fun onOutgoingCallRinging(out: Outgoing?) {
        Log.i(TAG, "outgoing ringing")
        _call.update { if (it != null && it.phase == CallPhase.DIALING) it.copy(phase = CallPhase.RINGING_OUT) else it }
    }

    override fun onOutgoingCallAnswered(out: Outgoing?) {
        Log.i(TAG, "outgoing answered")
        markAnswered()
    }

    override fun onOutgoingCallRejected(out: Outgoing?) {
        Log.i(TAG, "outgoing rejected")
        finish(Outcome.FAILED, "Call not answered")
    }

    override fun onOutgoingCallHangup(out: Outgoing?) {
        if (_call.value?.direction != Direction.OUTGOING) {
            Log.i(TAG, "outgoing hangup ignored, on-screen call is ${_call.value?.direction} ${_call.value?.phase}")
            return
        }
        Log.i(TAG, "outgoing hangup")
        onRemoteEnd()
    }

    override fun onOutgoingCallInvalid(out: Outgoing?) {
        Log.i(TAG, "outgoing invalid")
        finish(Outcome.FAILED, "Invalid number")
    }

    private fun onRemoteEnd() {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        val answered = state.answeredAt != null
        when {
            state.direction == Direction.INCOMING && userDeclined -> finish(Outcome.DECLINED, "Declined")
            state.direction == Direction.INCOMING && !answered -> finish(Outcome.MISSED, "Missed call")
            answered -> finish(Outcome.COMPLETED, "Call ended")
            else -> finish(Outcome.FAILED, "Call ended")
        }
    }

    // ---- SDK call-event hook ------------------------------------------------------------------

    /**
     * Native delivers TERMINATE_CALL here. The outgoing branch of the SDK listener calls
     * getOutgoing().reset() and then the hangup callback; a null outgoing leg throws and the UI
     * never hears about it. Status 200 never reaches that listener at all.
     * CallEvent is not visible outside the SDK, so the listener is wrapped by reflection.
     */
    private fun installCallHook(ep: Endpoint) {
        if (callHookInstalled) return
        val sc = runCatching { SipController.getInstance(ep) }.getOrNull() ?: return
        val field = runCatching {
            SipController::class.java.getDeclaredField("onCallEventListener").apply { isAccessible = true }
        }.getOrElse {
            Log.e(TAG, "call hook unavailable: ${it.javaClass.simpleName}")
            return
        }
        val existing = field.get(sc)
        if (existing == null) {
            Log.e(TAG, "call hook unavailable: SDK listener missing")
            return
        }
        val listenerClass = Class.forName("com.plivo.endpoint.SipController\$OnCallEventListener")
        var proxy: Any? = null
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@InvocationHandler when (method.name) {
                    "toString" -> "CallHook"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.getOrNull(0) === proxy
                    else -> null
                }
            }
            if (method.name == "onCallEvent") {
                val eventName = (args?.getOrNull(0) as? Enum<*>)?.name
                val payload = args?.getOrNull(1) as? String
                try {
                    onSdkCallEvent(eventName, payload)
                } catch (t: Throwable) {
                    Log.e(TAG, "call hook $eventName failed: ${t.javaClass.simpleName}")
                }
                try {
                    method.invoke(existing, *(args ?: emptyArray()))
                } catch (t: Throwable) {
                    val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                    Log.e(TAG, "sdk call listener $eventName threw ${cause.javaClass.simpleName}")
                }
            }
            null
        }
        proxy = java.lang.reflect.Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
        field.set(sc, proxy)
        callHookInstalled = true
        Log.i(TAG, "call hook installed")
    }

    private fun onSdkCallEvent(eventName: String?, payload: String?) {
        when (eventName) {
            "INCOMING_CALL" -> {
                val info = describeInvite(payload)
                val phase = _call.value?.phase
                val running = sdkCallRunning()
                Log.i(TAG, "INVITE received ${info.summary()} localPhase=${phase ?: "none"} sdkCallRunning=$running")
                if (phase != null && phase != CallPhase.ENDED) {
                    Log.w(TAG, "INVITE while a call is still $phase; it can be rejected busy/486 until that call is cleared")
                }
            }
            "TERMINATE_CALL" -> {
                Log.i(TAG, "TERMINATE_CALL ${summarizeSignal(payload)} localPhase=${_call.value?.phase} sdkCallRunning=${sdkCallRunning()}")
                main.post { onRemoteEnd() }
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val state = _call.value ?: return
            val phase = state.phase
            if (phase == CallPhase.ENDED) return
            val stats = signallingPresent()
            if (stats == true) sawSignalling = true
            val outActive = outgoingActive()
            // Status 200 nulls signalling and returns without a hangup callback, leaving
            // Outgoing active so the next INVITE is busy. RINGING_IN has neither signal.
            val dropped = phase != CallPhase.RINGING_IN &&
                (outActive == false || (sawSignalling && stats == false))
            if (dropped) {
                Log.w(
                    TAG,
                    "watchdog teardown phase=$phase outgoingActive=$outActive signalling=$stats sdkCallRunning=${sdkCallRunning()}",
                )
                onRemoteEnd()
                return
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun releaseSdkCall() {
        val reset = runCatching {
            Class.forName("com.plivo.endpoint.IO").getDeclaredMethod("reset").apply { isAccessible = true }
        }.getOrNull()
        if (reset != null) {
            outgoing?.let { runCatching { reset.invoke(it) } }
            incoming?.let { runCatching { reset.invoke(it) } }
        }
        // endCall even when the Java leg never went inactive. Native rejects a new INVITE
        // with 486 while it still has that session.
        runCatching { controller()?.hangup() }
            .onFailure { Log.w(TAG, "session release failed: ${it.javaClass.simpleName}") }
        main.post {
            val sc = controller() ?: return@post
            runCatching {
                SipController::class.java.getDeclaredField("incoming").apply { isAccessible = true }.set(sc, null)
                SipController::class.java.getDeclaredField("isIncomingCall").apply { isAccessible = true }
                    .set(sc, java.lang.Boolean.FALSE)
            }
        }
    }

    private fun controller(): SipController? =
        endpoint?.let { runCatching { SipController.getInstance(it) }.getOrNull() }

    private fun signallingPresent(): Boolean? {
        val sc = controller() ?: return null
        return runCatching {
            SipController::class.java.getDeclaredField("signallingStats").apply { isAccessible = true }.get(sc) != null
        }.getOrNull()
    }

    private fun outgoingActive(): Boolean? {
        val out = outgoing ?: return null
        return runCatching {
            Class.forName("com.plivo.endpoint.IO").getDeclaredMethod("isActive").apply { isAccessible = true }
                .invoke(out) as Boolean
        }.getOrNull()
    }

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

private data class InviteInfo(
    val fromUser: String?,
    val callId: String?,
    val cseq: String?,
) {
    fun summary(): String = "callId=${callId ?: "?"} from=${fromUser ?: "unknown"} cseq=${cseq ?: "?"}"
}

/** Caller, Call-ID, and CSeq only. Authorization and password headers are dropped. */
private fun describeInvite(raw: String?): InviteInfo {
    val headers = HashMap<String, String>()
    for (line in raw.orEmpty().split('\n')) {
        val trimmed = line.trim().trimEnd('\r')
        val idx = trimmed.indexOf(':')
        if (idx <= 0) continue
        val name = trimmed.substring(0, idx).trim().lowercase()
        if (name == "authorization" || name == "proxy-authorization" || name == "www-authenticate") continue
        if ("password" in name) continue
        if (name !in headers) headers[name] = trimmed.substring(idx + 1).trim()
    }
    val from = headers["p-asserted-identity"] ?: headers["from"] ?: headers["remote-party-id"]
    val callId = headers["call-id"] ?: headers["i"]
    return InviteInfo(
        fromUser = sipUser(from),
        callId = callId?.substringBefore(';')?.trim()?.take(80),
        cseq = headers["cseq"]?.take(40),
    )
}

private fun summarizeSignal(raw: String?): String {
    val text = raw.orEmpty()
    if (text.contains("From:", ignoreCase = true) || text.contains("Call-ID", ignoreCase = true)) {
        return describeInvite(text).summary()
    }
    val cleaned = text.replace(Regex("(?i)(password|authorization)\\s*[=:]\\s*\\S+"), "$1=<redacted>")
    return cleaned.take(160).ifBlank { "no-detail" }
}

private fun sipUser(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val body = value.substringAfter('<', value).substringBefore('>').trim()
    val user = body.removePrefix("sip:").removePrefix("sips:").removePrefix("tel:")
        .substringBefore('@').substringBefore(';').trim().trim('"')
    if (user.isBlank() || user.any { it.isWhitespace() } || user.length > 64) return null
    return user
}
