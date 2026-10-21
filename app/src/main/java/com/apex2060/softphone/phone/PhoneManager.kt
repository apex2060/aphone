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

/** What the owner can read on the phone. Never includes the SIP password or provisioning key. */
data class LineStatus(
    val registration: Registration = Registration.OFFLINE,
    val sdkRegistered: Boolean = false,
    val regTimeoutSec: Int = 0,
    val username: String? = null,
    val lastRegisterResult: String = "none",
    val lastRegisterAt: Long? = null,
    val lastRefreshReason: String? = null,
    val lastRefreshAt: Long? = null,
    val lastInviteAt: Long? = null,
    val lastInviteFrom: String? = null,
    val lastInviteCallId: String? = null,
    val loginAttemptAt: Long? = null,
    val lastSdkReason: String? = null,
    val lastCallResult: String = "none",
    val lastCallAt: Long? = null,
)

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

    /** Shortest expiry the SDK accepts. A 30-day REGISTER lets NAT drop inbound INVITEs. */
    private const val REG_TIMEOUT_SEC = 120
    private const val REFRESH_DEBOUNCE_MS = 15_000L

    /** How long one login attempt may sit on Connecting before it is shown as a failure. */
    private const val LOGIN_TIMEOUT_MS = 20_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private val _registration = MutableStateFlow(Registration.OFFLINE)
    val registration: StateFlow<Registration> = _registration

    private val _lineStatus = MutableStateFlow(LineStatus())
    val lineStatus: StateFlow<LineStatus> = _lineStatus

    private val _call = MutableStateFlow<CallState?>(null)
    val call: StateFlow<CallState?> = _call

    private var endpoint: Endpoint? = null
    private var incoming: Incoming? = null
    private var outgoing: Outgoing? = null
    private var userDeclined = false
    private var callHookInstalled = false
    private var sawSignalling = false
    /** Native BYE/CANCEL/reject already requested for the call now on screen. */
    private var sdkHangupSent = false
    /** Last REGISTER failed inside the SDK before any SIP response. Wi-Fi can still work. */
    private var localStackFailure = false
    private var localStackLogged = false
    private var lastNetworkKind: String? = null

    private var creds: Pair<String, String>? = null
    private var pendingCreds: Pair<String, String>? = null
    private var wantOnline = false
    private var retryDelayMs = RETRY_MIN_MS
    private var lastRegisterResult = "none"
    private var lastRegisterAt: Long? = null
    private var lastRefreshReason: String? = null
    private var lastRefreshAt: Long? = null
    private var lastRefreshAtMs = 0L
    private var lastInviteAt: Long? = null
    private var lastInviteFrom: String? = null
    private var lastInviteCallId: String? = null
    private var loginAttemptAt: Long? = null
    private var lastSdkReason: String? = null
    private var lastCallResult = "none"
    private var lastCallAt: Long? = null

    @Volatile private var foregroundActivities = 0

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** MainActivity and CallActivity. The service uses this to decide if it can open the call screen. */
    fun onActivityStarted() { foregroundActivities++ }

    fun onActivityStopped() { if (foregroundActivities > 0) foregroundActivities-- }

    fun isAppForeground(): Boolean = foregroundActivities > 0

    /** Safe to log. Username only — never the SIP password. */
    fun registrationSnapshot(): String {
        val ep = endpoint
        return "state=${_registration.value} sdkRegistered=${ep?.registered == true} " +
            "regTimeout=${ep?.registrationTimeout ?: 0}s user=${creds?.first ?: "-"} " +
            "lastRegister=$lastRegisterResult sdkReason=${lastSdkReason ?: "-"}"
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
        applyRegTimeout(ep)
        if (creds == next && ep.registered && _registration.value == Registration.ONLINE) {
            publishLine()
            return
        }
        // One attempt at a time. A second login() while the first is outstanding
        // returns without a callback and leaves the UI on Connecting.
        if (creds == next && _registration.value == Registration.CONNECTING) {
            publishLine()
            return
        }
        if (creds != null && creds != next && ep.registered) {
            // Credentials rotated: log out first, onLogout() logs back in with the new pair.
            pendingCreds = next
            ep.logout()
            return
        }
        creds = next
        if (!sendLogin(ep)) scheduleRetry()
    }

    /**
     * Re-bind the contact so an inbound INVITE still has somewhere to land.
     * [reason] is `resume`, `network`, or `timer`. No-op during a call, and it does not
     * start a second login while one attempt is still waiting on Plivo.
     */
    fun refreshRegistration(reason: String) {
        val ep = endpoint ?: return
        if (!wantOnline) return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAtMs < REFRESH_DEBOUNCE_MS) return
        lastRefreshAtMs = now
        lastRefreshReason = reason
        lastRefreshAt = now
        if (_registration.value == Registration.CONNECTING) {
            Log.i(TAG, "registration refresh skipped reason=$reason login in progress")
            publishLine()
            return
        }
        applyRegTimeout(ep)
        if (activeCall() != null) {
            Log.i(TAG, "registration refresh skipped reason=$reason phase=${_call.value?.phase}")
            publishLine()
            return
        }
        Log.i(TAG, "registration refresh reason=$reason ${registrationSnapshot()}")
        if (ep.registered) {
            // login() returns true and does not call onLogin when the SDK is already registered.
            if (_registration.value != Registration.ONLINE) onLogin()
            runCatching { ep.networkChange() }
                .onFailure { Log.w(TAG, "networkChange failed: ${it.javaClass.simpleName}") }
            publishLine()
        } else {
            login(creds?.first ?: return, creds?.second ?: return)
        }
    }

    /**
     * The 3.2.2-beta stack binds IPv4 TLS only. There is no setup option for IPv6, UDP, or
     * the bind address, so an IPv6-only or 464XLAT cellular network cannot be fixed here.
     * When that failure is followed by a different default network (usually Wi-Fi), drop the
     * endpoint that already failed to bind and register again. Recreating it on the same
     * cellular network does not help, so this runs only when the network kind changes.
     */
    fun onDefaultNetwork(kind: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onDefaultNetwork(kind) }
            return
        }
        val changed = lastNetworkKind != null && lastNetworkKind != kind && kind != "none"
        lastNetworkKind = kind
        val ep = endpoint
        if (changed && localStackFailure && ep?.registered != true && activeCall() == null && wantOnline) {
            Log.i(TAG, "recreating endpoint on network=$kind after a local stack failure")
            dropEndpoint()
            main.removeCallbacks(loginTimeout)
            main.removeCallbacks(retry)
            lastRefreshAtMs = 0L
            _registration.value = Registration.FAILED
            val c = creds
            if (c != null) {
                login(c.first, c.second)
                return
            }
        }
        refreshRegistration("network")
    }

    private fun dropEndpoint() {
        val old = endpoint
        endpoint = null
        callHookInstalled = false
        incoming = null
        outgoing = null
        runCatching { old?.resetEndpoint() }
            .onFailure { Log.w(TAG, "resetEndpoint failed: ${it.javaClass.simpleName}") }
        // resetEndpoint nulls these on success. Clear them anyway so the next
        // newInstance does not reuse a stack whose bind already failed.
        runCatching {
            Endpoint::class.java.getDeclaredField("endpoint").apply { isAccessible = true }.set(null, null)
        }
        runCatching {
            SipController::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(null, null)
        }
    }

    /** Starts one REGISTER. False means the SDK rejected it immediately. */
    private fun sendLogin(ep: Endpoint): Boolean {
        val c = creds ?: return false
        _registration.value = Registration.CONNECTING
        loginAttemptAt = System.currentTimeMillis()
        publishLine()
        Log.i(TAG, "login user=${c.first} regTimeout=${ep.registrationTimeout}s")
        val started = runCatching { ep.login(c.first, c.second) }.getOrDefault(false)
        if (!started) return false
        if (ep.registered) {
            Log.i(TAG, "SDK already registered; not waiting for another onLogin")
            onLogin()
            return true
        }
        armLoginTimeout()
        lastRefreshAtMs = System.currentTimeMillis()
        return true
    }

    private fun armLoginTimeout() {
        main.removeCallbacks(loginTimeout)
        main.postDelayed(loginTimeout, LOGIN_TIMEOUT_MS)
    }

    private val loginTimeout = Runnable {
        if (!wantOnline || _registration.value != Registration.CONNECTING) return@Runnable
        if (endpoint?.registered == true) {
            Log.i(TAG, "login timeout but SDK is registered; marking online")
            onLogin()
            return@Runnable
        }
        val detail = lastSdkReason?.take(80)
        lastRegisterResult = if (detail.isNullOrBlank()) "timeout" else "timeout ($detail)"
        lastRegisterAt = System.currentTimeMillis()
        noteLocalStackFailure(lastRegisterResult)
        Log.w(TAG, "login timed out after ${LOGIN_TIMEOUT_MS / 1000}s ${registrationSnapshot()}")
        scheduleRetry()
    }

    private fun applyRegTimeout(ep: Endpoint) {
        ep.setRegTimeout(REG_TIMEOUT_SEC)
        runCatching { SipController.getInstance(ep).registerTimeOut(REG_TIMEOUT_SEC) }
            .onFailure { Log.w(TAG, "registerTimeOut failed: ${it.javaClass.simpleName}") }
    }

    fun logout() {
        wantOnline = false
        pendingCreds = null
        main.removeCallbacks(retry)
        main.removeCallbacks(loginTimeout)
        main.removeCallbacks(watchdog)
        endpoint?.let { if (it.registered) it.logout() }
        creds = null
        lastRegisterResult = "logged-out"
        _registration.value = Registration.OFFLINE
        publishLine()
    }

    private val retry = Runnable {
        val ep = endpoint ?: return@Runnable
        if (!wantOnline || _registration.value == Registration.ONLINE) return@Runnable
        if (_registration.value == Registration.CONNECTING) return@Runnable
        applyRegTimeout(ep)
        Log.i(TAG, "login retry user=${creds?.first ?: "-"} regTimeout=${ep.registrationTimeout}s")
        if (!sendLogin(ep)) scheduleRetry()
    }

    private fun scheduleRetry() {
        main.removeCallbacks(loginTimeout)
        _registration.value = Registration.FAILED
        publishLine()
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun publishLine() {
        val ep = endpoint
        _lineStatus.value = LineStatus(
            registration = _registration.value,
            sdkRegistered = ep?.registered == true,
            regTimeoutSec = ep?.registrationTimeout ?: 0,
            username = creds?.first,
            lastRegisterResult = lastRegisterResult,
            lastRegisterAt = lastRegisterAt,
            lastRefreshReason = lastRefreshReason,
            lastRefreshAt = lastRefreshAt,
            lastInviteAt = lastInviteAt,
            lastInviteFrom = lastInviteFrom,
            lastInviteCallId = lastInviteCallId,
            loginAttemptAt = loginAttemptAt,
            lastSdkReason = lastSdkReason,
            lastCallResult = lastCallResult,
            lastCallAt = lastCallAt,
        )
    }

    override fun onLogin() {
        main.removeCallbacks(loginTimeout)
        main.removeCallbacks(retry)
        lastRegisterResult = "ok"
        lastRegisterAt = System.currentTimeMillis()
        localStackFailure = false
        localStackLogged = false
        retryDelayMs = RETRY_MIN_MS
        _registration.value = Registration.ONLINE
        publishLine()
        Log.i(TAG, "registered ${registrationSnapshot()}")
    }

    override fun onLogout() {
        Log.i(TAG, "logged out")
        lastRegisterResult = "logged-out"
        _registration.value = Registration.OFFLINE
        publishLine()
        val next = pendingCreds ?: return
        pendingCreds = null
        creds = null
        main.post { login(next.first, next.second) }
    }

    override fun onLoginFailed() = onLoginFailed(null)

    override fun onLoginFailed(reason: String?) {
        main.removeCallbacks(loginTimeout)
        val detail = lastSdkReason?.take(120)
        val code = reason?.takeIf { it.isNotBlank() }
        lastRegisterResult = when {
            code == null && detail.isNullOrBlank() -> "failed:no-reason"
            code == null -> "failed:$detail"
            detail.isNullOrBlank() || detail == code -> "failed:$code"
            else -> "failed:$code ($detail)"
        }.take(180)
        lastRegisterAt = System.currentTimeMillis()
        noteLocalStackFailure(lastRegisterResult)
        Log.w(TAG, "login failed: $lastRegisterResult user=${creds?.first ?: "-"}")
        main.post {
            publishLine()
            if (wantOnline) scheduleRetry()
        }
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
        val ep = endpoint ?: run {
            noteCall("blocked:no-endpoint")
            return false
        }
        val loggedIn = _registration.value == Registration.ONLINE || ep.registered
        if (!loggedIn || activeCall() != null) {
            noteCall("blocked:${_registration.value.name.lowercase()}")
            return false
        }
        val number = normalize(raw)
        if (number.isEmpty()) return false
        val out = ep.createOutgoingCall() ?: run {
            noteCall("failed:no-outgoing")
            return false
        }
        outgoing = out
        sawSignalling = false
        sdkHangupSent = false
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
        if (_call.value?.phase != CallPhase.RINGING_IN) return
        Log.i(TAG, "answer incoming from=${_call.value?.number}")
        val answered = runCatching { incoming?.answer() == true }.getOrDefault(false)
        if (!answered) {
            Log.w(TAG, "Incoming.answer unavailable, using SipController.doAnswer")
            runCatching { controller()?.doAnswer() }
                .onFailure { Log.e(TAG, "doAnswer failed: ${it.javaClass.simpleName}") }
        }
        markAnswered()
    }

    fun decline() {
        val state = _call.value ?: return
        if (state.phase != CallPhase.RINGING_IN) return
        userDeclined = true
        Log.i(TAG, "decline incoming from=${state.number}")
        finish(Outcome.DECLINED, "Declined")
    }

    fun hangup() {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        if (state.phase == CallPhase.RINGING_IN && state.answeredAt == null) {
            decline()
            return
        }
        Log.i(TAG, "local hangup phase=${state.phase} direction=${state.direction} sdkCallRunning=${sdkCallRunning()}")
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
        var answered = false
        _call.update {
            // Plivo fires "connected" from an ICE observer, which can arrive after the call already ended.
            if (it == null || it.phase == CallPhase.ENDED) return@update it
            answered = true
            it.copy(phase = CallPhase.ACTIVE, answeredAt = it.answeredAt ?: System.currentTimeMillis())
        }
        if (!answered) return
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Records history, drops in-call audio, and clears both the UI call and the SDK's
     * "call running" flag. Idempotent: a late callback cannot resurrect the call or log it twice.
     */
    private fun finish(outcome: Outcome, label: String) {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        // Native BYE/CANCEL/reject before local reset, and outside the teardown lock.
        // endCall invokes the SDK listener before it returns; that listener calls back
        // into finish on this thread or the SIP thread.
        signalSdkHangup(state)
        synchronized(this) {
            val current = _call.value ?: return
            if (current.phase == CallPhase.ENDED || current.startedAt != state.startedAt) return
            finishLocked(current, outcome, label)
        }
    }

    private fun finishLocked(state: CallState, outcome: Outcome, label: String) {
        _call.value = state.copy(phase = CallPhase.ENDED, endedLabel = label, outcome = outcome)
        noteCall("${outcome.name.lowercase()}:$label")
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
            // The SDK calls this with a null Incoming for a live SIP INVITE. Status 180 still
            // means the session is ringing; start the UI here when the call hook did not.
            Log.w(TAG, "onIncomingCall null Incoming localPhase=${phase ?: "none"} sdkCallRunning=$running")
            if (activeCall() == null) {
                val recent = lastInviteAt != null && System.currentTimeMillis() - lastInviteAt!! < 5_000
                val number = if (recent) lastInviteFrom ?: "Unknown" else "Unknown"
                Log.i(TAG, "onIncomingCall null Incoming; showing ring from=$number")
                main.post { showIncoming(number, null) }
            }
            return
        }
        val from = callerOf(inc)
        if (activeCall() != null && _call.value?.phase != CallPhase.RINGING_IN) {
            Log.w(TAG, "incoming refused, line busy from=$from localPhase=$phase sdkCallRunning=$running")
            inc.reject()
            CallHistory.add(
                from, Direction.INCOMING, Outcome.MISSED, System.currentTimeMillis(), 0,
                ConfigStore.config.value?.historyLimit ?: 200,
            )
            return
        }
        Log.i(TAG, "onIncomingCall from=$from sdkCallRunning=$running")
        main.post { showIncoming(from, inc) }
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

    private fun showIncoming(number: String, inc: Incoming?) {
        if (_call.value?.phase == CallPhase.RINGING_IN) {
            if (inc != null) incoming = inc
            return
        }
        if (activeCall() != null) return
        incoming = inc
        userDeclined = false
        sawSignalling = false
        sdkHangupSent = false
        _call.value = CallState(Direction.INCOMING, number.ifBlank { "Unknown" }, CallPhase.RINGING_IN)
        armWatchdog()
        Log.i(TAG, "incoming call shown from=$number ${registrationSnapshot()}")
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
        installRegistrationHook(sc)
        Log.i(TAG, "call hook installed")
    }

    /**
     * Username/password failures call [onLoginFailed] with no string. The SIP status
     * (for example 401) is only on this listener. Record it for Details, then forward.
     */
    private fun installRegistrationHook(sc: SipController) {
        val field = runCatching {
            SipController::class.java.getDeclaredField("onRegistrationEventListener").apply { isAccessible = true }
        }.getOrElse {
            Log.e(TAG, "registration hook unavailable: ${it.javaClass.simpleName}")
            return
        }
        val existing = field.get(sc) ?: run {
            Log.e(TAG, "registration hook unavailable: SDK listener missing")
            return
        }
        val listenerClass = Class.forName("com.plivo.endpoint.SipController\$OnRegistrationEventListener")
        var proxy: Any? = null
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@InvocationHandler when (method.name) {
                    "toString" -> "RegHook"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.getOrNull(0) === proxy
                    else -> null
                }
            }
            if (method.name == "onRegistrationEvent") {
                val eventName = (args?.getOrNull(0) as? Enum<*>)?.name
                val clean = sanitizeSdkMessage(args?.getOrNull(1) as? String)
                lastSdkReason = listOfNotNull(eventName, clean).joinToString(": ").ifBlank { eventName }
                Log.i(TAG, "registration event ${lastSdkReason ?: "none"}")
                try {
                    method.invoke(existing, *(args ?: emptyArray()))
                } catch (t: Throwable) {
                    val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                    Log.e(TAG, "sdk registration listener threw ${cause.javaClass.simpleName}")
                }
                main.post { publishLine() }
            }
            null
        }
        proxy = java.lang.reflect.Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
        field.set(sc, proxy)
    }

    private fun noteCall(result: String) {
        lastCallResult = result.take(120)
        lastCallAt = System.currentTimeMillis()
        Log.i(TAG, "call result $lastCallResult")
        if (Looper.myLooper() == Looper.getMainLooper()) publishLine() else main.post { publishLine() }
    }

    private fun onSdkCallEvent(eventName: String?, payload: String?) {
        when (eventName) {
            "INCOMING_CALL" -> {
                val info = describeInvite(payload)
                val phase = _call.value?.phase
                val running = sdkCallRunning()
                lastInviteAt = System.currentTimeMillis()
                lastInviteFrom = info.fromUser ?: "unknown"
                lastInviteCallId = info.callId
                main.post { publishLine() }
                Log.i(
                    TAG,
                    "INVITE received ${info.summary()} localPhase=${phase ?: "none"} " +
                        "sdkCallRunning=$running ${registrationSnapshot()}",
                )
                if (phase != null && phase != CallPhase.ENDED && phase != CallPhase.RINGING_IN) {
                    Log.w(TAG, "INVITE while a call is still $phase; rejecting busy")
                    runCatching { controller()?.reject() }
                    val from = info.fromUser ?: "Unknown"
                    main.post {
                        CallHistory.add(
                            from, Direction.INCOMING, Outcome.MISSED, System.currentTimeMillis(), 0,
                            ConfigStore.config.value?.historyLimit ?: 200,
                        )
                    }
                    return
                }
                // The SDK only builds Incoming for a push relay. Attach one so answer/reject
                // reach the native session that is already handling this INVITE. Set the ring
                // before returning: the 180 that follows calls onIncomingCall on this same thread.
                val inc = bindIncoming(info)
                val number = info.fromUser ?: "Unknown"
                showIncoming(number, inc)
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

    /**
     * Ask the SDK to end the dialog, then return. Local reset happens only after this.
     * Outgoing.hangup and SipController.hangup close the PeerConnection before endCall.
     * Closing it first can mark the native session done, and endCall then returns
     * without a BYE. Incoming.reject is only for a call that has not been answered;
     * an answered incoming call must BYE, not reject.
     */
    private fun signalSdkHangup(state: CallState) {
        synchronized(this) {
            if (sdkHangupSent) return
            sdkHangupSent = true
        }
        val unanswered = state.direction == Direction.INCOMING &&
            state.phase == CallPhase.RINGING_IN &&
            state.answeredAt == null
        if (unanswered) {
            Log.i(TAG, "sdk reject before teardown from=${state.number}")
            val rejected = runCatching { incoming?.reject() == true }.getOrDefault(false)
            if (!rejected) {
                Log.i(TAG, "Incoming.reject declined; calling SipController.reject")
                runCatching { controller()?.reject() }
                    .onFailure { Log.w(TAG, "reject failed: ${it.javaClass.simpleName}") }
            }
            return
        }
        Log.i(
            TAG,
            "sdk endCall before teardown phase=${state.phase} direction=${state.direction} " +
                "outgoing=${outgoing != null} incoming=${incoming != null}",
        )
        // endCall sends the BYE or CANCEL. The leg hangup() also reaches it, but only
        // after closing the PeerConnection, so it runs after endCall has returned.
        runCatching { controller()?.endCall() }
            .onFailure { Log.w(TAG, "endCall failed: ${it.javaClass.simpleName}") }
        val sent = if (state.direction == Direction.INCOMING) {
            runCatching { incoming?.hangup() == true }.getOrDefault(false) ||
                runCatching { outgoing?.hangup() == true }.getOrDefault(false)
        } else {
            runCatching { outgoing?.hangup() == true }.getOrDefault(false) ||
                runCatching { incoming?.hangup() == true }.getOrDefault(false)
        }
        if (!sent) Log.i(TAG, "leg hangup did not accept; endCall was already requested")
    }

    private fun releaseSdkCall() {
        val reset = runCatching {
            Class.forName("com.plivo.endpoint.IO").getDeclaredMethod("reset").apply { isAccessible = true }
        }.getOrNull()
        if (reset != null) {
            outgoing?.let { runCatching { reset.invoke(it) } }
            incoming?.let { runCatching { reset.invoke(it) } }
        }
        closePeerConnection()
        main.post {
            val sc = controller() ?: return@post
            runCatching {
                SipController::class.java.getDeclaredField("incoming").apply { isAccessible = true }.set(sc, null)
                SipController::class.java.getDeclaredField("isIncomingCall").apply { isAccessible = true }
                    .set(sc, java.lang.Boolean.FALSE)
            }
        }
    }

    /**
     * The SDK constructs [Incoming] only inside relayVoipPushNotification. A live INVITE still
     * reaches native, which then calls onIncomingCall with whatever is in this field — null,
     * unless we put a call here first. Answer and reject both forward to the native session.
     * [from] is the caller user as received, digits with no leading '+' when that is what Plivo sent.
     */
    private fun bindIncoming(info: InviteInfo): Incoming? {
        val sc = controller()
        if (sc == null) {
            Log.e(TAG, "bind Incoming skipped, no SipController")
            return null
        }
        val fromHeader = info.fromHeader?.takeIf { it.isNotBlank() }
            ?: info.fromUser?.let { "sip:$it" }
            ?: "sip:unknown"
        val stir = info.headers["x-plivo-stir-verification"] ?: "Not applicable"
        return runCatching {
            val inc = Incoming(fromHeader, "", java.util.HashMap(info.headers), stir, sc)
            info.callId?.let { id ->
                runCatching {
                    Class.forName("com.plivo.endpoint.IO").getDeclaredField("callId")
                        .apply { isAccessible = true }
                        .set(inc, id)
                }
            }
            SipController::class.java.getDeclaredField("incoming").apply { isAccessible = true }.set(sc, inc)
            Log.i(TAG, "bound Incoming from=${info.fromUser ?: "unknown"} callId=${info.callId ?: "?"}")
            inc
        }.getOrElse {
            Log.e(TAG, "bind Incoming failed: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun controller(): SipController? =
        endpoint?.let { runCatching { SipController.getInstance(it) }.getOrNull() }

    /** After endCall. [SipController.hangup] closes this first and can drop the BYE. */
    private fun closePeerConnection() {
        val sc = controller() ?: return
        runCatching {
            val field = SipController::class.java.getDeclaredField("pc").apply { isAccessible = true }
            val pc = field.get(sc) ?: return@runCatching
            pc.javaClass.getMethod("close").invoke(pc)
            field.set(sc, null)
        }.onFailure { Log.w(TAG, "peer connection close failed: ${it.javaClass.simpleName}") }
    }

    private fun noteLocalStackFailure(text: String?) {
        if (!describesLocalStackFailure(text)) return
        localStackFailure = true
        if (localStackLogged) return
        localStackLogged = true
        Log.w(
            TAG,
            "REGISTER failed in the SDK before a SIP response. 3.2.2-beta is IPv4 TLS only " +
                "and has no transport or IPv6 option. Wi-Fi can register; this cellular network cannot.",
        )
    }

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
    val fromHeader: String?,
    val callId: String?,
    val cseq: String?,
    val headers: HashMap<String, String>,
) {
    fun summary(): String = "callId=${callId ?: "?"} from=${fromUser ?: "unknown"} cseq=${cseq ?: "?"}"
}

private val INVITE_HEADERS = setOf(
    "from", "to", "call-id", "i", "cseq", "contact",
    "p-asserted-identity", "remote-party-id", "x-plivo-stir-verification",
)

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
        if (name !in INVITE_HEADERS || name in headers) continue
        headers[name] = trimmed.substring(idx + 1).trim().take(180)
    }
    // Plivo Dial callerId is the original caller, often digits with no leading '+'.
    val from = headers["p-asserted-identity"] ?: headers["from"] ?: headers["remote-party-id"]
    val callId = headers["call-id"] ?: headers["i"]
    return InviteInfo(
        fromUser = sipUser(from),
        fromHeader = from?.take(180),
        callId = callId?.substringBefore(';')?.trim()?.take(80),
        cseq = headers["cseq"]?.take(40),
        headers = headers,
    )
}

/**
 * True when the SDK failed while building the REGISTER, before Plivo answered.
 * The Details text is often the resip pre-send log (`Via: SIP/2.0/ ;branch=...`),
 * which is empty until a transport is selected. That is not a 401.
 */
fun describesLocalStackFailure(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val r = text.lowercase()
    return "makeinitialrequest" in r ||
        "can't find source address" in r ||
        "cant find source address" in r ||
        "no transport" in r ||
        "could not bind" in r ||
        "incomplete via" in r ||
        "use_ipv6" in r ||
        "ipv6 support is not enabled" in r ||
        "network not initialized" in r
}

/** Status line or SDK reason code. Authorization and password text are dropped. */
private fun sanitizeSdkMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val kept = raw.lineSequence().map { it.trim().trimEnd('\r') }.filter { line ->
        val lower = line.lowercase()
        "authorization" !in lower && "password" !in lower && "x-provision" !in lower
    }.joinToString(" ")
    val cleaned = kept.replace(Regex("(?i)(password|authorization)\\s*[=:]\\s*\\S+"), "$1=<redacted>")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(160)
    return cleaned.ifBlank { null }
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
        .substringBefore('@').substringBefore(';').substringBefore(':').trim().trim('"')
    if (user.isBlank() || user.any { it.isWhitespace() } || user.length > 64) return null
    return user
}
