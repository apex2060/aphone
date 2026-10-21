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
    val pushEnabled: Boolean = false,
    val fcmTokenMasked: String = "none",
    val lastPushAt: Long? = null,
    val lastPushFrom: String? = null,
    val lastRelayResult: String = "none",
    val stayRegistered: Boolean = false,
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
    private var deviceToken: String? = null
    private var loggedInWithDeviceToken = false
    private var loginReason = "login"
    private var reloginAfterLogout = false
    private var pendingPush: Map<String, String>? = null
    private var pendingDial: String? = null
    private var releaseResult: String? = null
    private var handlingPush = false
    private var lastPushAt: Long? = null
    private var lastPushFrom: String? = null
    private var lastRelayResult = "none"

    @Volatile private var foregroundActivities = 0

    fun init(context: Context) {
        app = context.applicationContext
        deviceToken = ConfigStore.fcmToken
    }

    val stayRegistered: Boolean get() = ConfigStore.stayRegistered

    /** MainActivity and CallActivity. The service uses this to decide if it can open the call screen. */
    fun onActivityStarted() { foregroundActivities++ }

    fun onActivityStopped() { if (foregroundActivities > 0) foregroundActivities-- }

    fun isAppForeground(): Boolean = foregroundActivities > 0

    /** Safe to log. Username only — never the SIP password. */
    fun registrationSnapshot(): String {
        val ep = endpoint
        return "state=${_registration.value} sdkRegistered=${ep?.registered == true} " +
            "regTimeout=${ep?.registrationTimeout ?: 0}s user=${creds?.first ?: "-"} lastRegister=$lastRegisterResult " +
            "push=${if (BuildConfig.PUSH_ENABLED) "on" else "off"} fcm=${maskToken(deviceToken)} relay=$lastRelayResult"
    }

    // ---- registration -------------------------------------------------------------------------

    /**
     * Registers the endpoint. [token] is the FCM token when push is in use; Plivo stores it on
     * this REGISTER and later wakes the app with a data message instead of a bare INVITE.
     * [reason] is `token` (publish the token, then drop the contact), `outgoing`, `push`, or `stay`.
     */
    fun login(username: String, password: String, token: String? = null, reason: String = "login") {
        val ep = ensureEndpoint()
        val next = username to password
        val previousToken = deviceToken
        val nextToken = token?.takeIf { it.isNotBlank() } ?: previousToken
        if (!nextToken.isNullOrBlank()) deviceToken = nextToken
        loginReason = reason
        wantOnline = true
        applyRegTimeout(ep)
        val sameCreds = creds == next
        val sameToken = nextToken == previousToken
        if (reason != "push" && sameCreds && sameToken &&
            _registration.value in setOf(Registration.ONLINE, Registration.CONNECTING)
        ) {
            publishLine()
            return
        }
        if (ep.registered && (!sameCreds || (nextToken != null && nextToken != previousToken))) {
            reloginAfterLogout = true
            creds = next
            ep.logout()
            return
        }
        creds = next
        _registration.value = Registration.CONNECTING
        publishLine()
        Log.i(TAG, "login user=$username reason=$reason regTimeout=${ep.registrationTimeout}s fcm=${maskToken(deviceToken)}")
        if (!sendLogin(ep)) scheduleRetry() else lastRefreshAtMs = System.currentTimeMillis()
    }

    private fun sendLogin(ep: Endpoint): Boolean {
        val c = creds ?: return false
        val token = deviceToken?.takeIf { it.isNotBlank() }
        val ok = if (token != null) ep.login(c.first, c.second, token) else ep.login(c.first, c.second)
        if (ok && token != null) loggedInWithDeviceToken = true
        return ok
    }

    private fun ensureEndpoint(): Endpoint =
        endpoint ?: Endpoint.newInstance(app, BuildConfig.DEBUG, this).also {
            endpoint = it
            installCallHook(it)
        }

    /**
     * Re-bind the contact. Used only while the debug "stay registered" toggle is on.
     * [reason] is `resume`, `network`, or `timer`. No-op during a call.
     */
    fun refreshRegistration(reason: String) {
        if (!stayRegistered) return
        val ep = endpoint ?: return
        if (!wantOnline) return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAtMs < REFRESH_DEBOUNCE_MS) return
        applyRegTimeout(ep)
        if (activeCall() != null) {
            Log.i(TAG, "registration refresh skipped reason=$reason phase=${_call.value?.phase}")
            return
        }
        lastRefreshAtMs = now
        lastRefreshReason = reason
        lastRefreshAt = now
        Log.i(TAG, "registration refresh reason=$reason ${registrationSnapshot()}")
        if (!ep.registered || _registration.value != Registration.ONLINE) {
            val c = creds ?: ConfigStore.config.value?.let { it.sipUsername to it.sipPassword } ?: return
            creds = c
            loginReason = "stay"
            _registration.value = Registration.CONNECTING
            publishLine()
            if (!sendLogin(ep)) scheduleRetry()
        } else {
            runCatching { ep.networkChange() }
                .onFailure { Log.w(TAG, "networkChange failed: ${it.javaClass.simpleName}") }
            publishLine()
        }
    }

    private fun applyRegTimeout(ep: Endpoint) {
        ep.setRegTimeout(REG_TIMEOUT_SEC)
        runCatching { SipController.getInstance(ep).registerTimeOut(REG_TIMEOUT_SEC) }
            .onFailure { Log.w(TAG, "registerTimeOut failed: ${it.javaClass.simpleName}") }
    }

    fun logout() {
        wantOnline = false
        pendingCreds = null
        pendingDial = null
        pendingPush = null
        reloginAfterLogout = false
        loggedInWithDeviceToken = false
        deviceToken = null
        main.removeCallbacks(retry)
        main.removeCallbacks(watchdog)
        endpoint?.let { if (it.registered) it.logout() }
        creds = null
        lastRegisterResult = "logged-out"
        _registration.value = Registration.OFFLINE
        publishLine()
    }

    /** Drop the SIP contact after the FCM token has been published, or after a call. */
    private fun releaseContact(result: String) {
        if (stayRegistered || activeCall() != null || pendingDial != null) return
        wantOnline = false
        main.removeCallbacks(retry)
        val ep = endpoint
        releaseResult = result
        lastRegisterResult = result
        if (ep?.registered == true) {
            Log.i(TAG, "releasing SIP contact result=$result ${registrationSnapshot()}")
            ep.logout()
        } else {
            loggedInWithDeviceToken = false
            _registration.value = Registration.OFFLINE
            publishLine()
            Log.i(TAG, "SIP contact already down result=$result")
        }
    }

    private val retry = Runnable {
        val c = creds ?: return@Runnable
        if (wantOnline && _registration.value != Registration.ONLINE) {
            val ep = endpoint ?: return@Runnable
            applyRegTimeout(ep)
            _registration.value = Registration.CONNECTING
            publishLine()
            Log.i(TAG, "login retry user=${c.first} reason=$loginReason regTimeout=${ep.registrationTimeout}s fcm=${maskToken(deviceToken)}")
            if (sendLogin(ep)) lastRefreshAtMs = System.currentTimeMillis() else scheduleRetry()
        }
    }

    private fun scheduleRetry() {
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
            pushEnabled = BuildConfig.PUSH_ENABLED,
            fcmTokenMasked = maskToken(deviceToken),
            lastPushAt = lastPushAt,
            lastPushFrom = lastPushFrom,
            lastRelayResult = lastRelayResult,
            stayRegistered = stayRegistered,
        )
    }

    override fun onLogin() {
        lastRegisterResult = "ok"
        lastRegisterAt = System.currentTimeMillis()
        retryDelayMs = RETRY_MIN_MS
        _registration.value = Registration.ONLINE
        publishLine()
        Log.i(TAG, "registered reason=$loginReason ${registrationSnapshot()}")
        val number = pendingDial
        if (number != null) {
            pendingDial = null
            main.post { placeOutgoing(number) }
            return
        }
        // A token publish is not a call. Drop the contact so Plivo sends a push next time
        // instead of an INVITE to a registration this process will not keep alive.
        if (loginReason == "token" && !stayRegistered && activeCall() == null) {
            main.post { releaseContact("token-registered") }
        }
    }

    override fun onLogout() {
        Log.i(TAG, "logged out")
        loggedInWithDeviceToken = false
        val push = pendingPush
        pendingPush = null
        if (push != null) {
            _registration.value = Registration.OFFLINE
            publishLine()
            main.post { onPush(push) }
            return
        }
        if (reloginAfterLogout) {
            reloginAfterLogout = false
            val next = creds
            _registration.value = Registration.OFFLINE
            publishLine()
            if (next != null) main.post { login(next.first, next.second, deviceToken, loginReason) }
            return
        }
        val next = pendingCreds
        pendingCreds = null
        if (next != null) {
            creds = null
            _registration.value = Registration.OFFLINE
            publishLine()
            main.post { login(next.first, next.second, deviceToken, loginReason) }
            return
        }
        val released = releaseResult
        releaseResult = null
        lastRegisterResult = released ?: if (loginReason == "token") "token-registered" else "logged-out"
        _registration.value = Registration.OFFLINE
        publishLine()
    }

    override fun onLoginFailed() = onLoginFailed("unknown")

    override fun onLoginFailed(reason: String?) {
        lastRegisterResult = "failed:${reason ?: "unknown"}"
        lastRegisterAt = System.currentTimeMillis()
        Log.w(TAG, "login failed: ${reason ?: "unknown"} user=${creds?.first ?: "-"}")
        if (handlingPush) {
            publishLine()
            return
        }
        val number = pendingDial
        main.post {
            publishLine()
            if (number != null) {
                pendingDial = null
                finish(Outcome.FAILED, "Can't reach Plivo")
            } else if (wantOnline) {
                scheduleRetry()
            }
        }
    }

    // ---- push ---------------------------------------------------------------------------------

    /** Fetch the FCM token and register it with Plivo. No-op when push is compiled out. */
    fun onProvisioned() {
        val cfg = ConfigStore.config.value ?: return
        if (!BuildConfig.PUSH_ENABLED) {
            lastRelayResult = "push-disabled"
            publishLine()
            Log.i(TAG, "push disabled (no google-services.json)")
            if (stayRegistered) login(cfg.sipUsername, cfg.sipPassword, reason = "stay")
            return
        }
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> onFcmToken(token) }
            .addOnFailureListener { err ->
                Log.w(TAG, "fcm token failed: ${err.javaClass.simpleName}")
                noteRelay("failed:token")
            }
    }

    fun onFcmToken(token: String) {
        if (token.isBlank()) return
        val changed = token != deviceToken
        deviceToken = token
        ConfigStore.fcmToken = token
        Log.i(TAG, "fcm token ${maskToken(token)}")
        publishLine()
        val cfg = ConfigStore.config.value ?: return
        if (!changed && (lastRegisterResult == "token-registered" || lastRegisterResult == "ok")) return
        val reason = if (stayRegistered) "stay" else "token"
        if (reason == "token" && (activeCall() != null || pendingDial != null)) return
        login(cfg.sipUsername, cfg.sipPassword, token, reason = reason)
    }

    /** Debug-only. Keeps a SIP registration and the refresh timer instead of waiting for push. */
    fun setStayRegistered(enabled: Boolean) {
        ConfigStore.stayRegistered = enabled
        publishLine()
        val cfg = ConfigStore.config.value ?: return
        if (enabled) {
            login(cfg.sipUsername, cfg.sipPassword, deviceToken, reason = "stay")
        } else if (activeCall() == null) {
            releaseContact("stay-off")
        }
    }

    /**
     * Plivo's high-priority data message. Required keys are registrar, index, and label.
     * [Endpoint.loginForIncomingWithUsername] registers from that message and constructs
     * the real [Incoming]. [Endpoint.relayVoipPushNotification] is the deprecated path.
     */
    fun onPush(data: Map<String, String>) {
        val from = sipUser(data["callerID"]) ?: data["callerID"]?.takeIf { it.length <= 64 } ?: "unknown"
        lastPushAt = System.currentTimeMillis()
        lastPushFrom = from
        Log.i(TAG, "push received from=$from keys=${data.keys.sorted().joinToString(",")}")
        if (!BuildConfig.PUSH_ENABLED) {
            noteRelay("skipped:push-disabled")
            return
        }
        val cfg = ConfigStore.config.value
        if (cfg == null) {
            noteRelay("failed:no-credentials")
            return
        }
        val missing = listOf("registrar", "index", "label").filter { it !in data }
        if (missing.isNotEmpty()) {
            noteRelay("failed:missing:${missing.joinToString("+")}")
            return
        }
        val token = deviceToken
        if (token.isNullOrBlank()) {
            noteRelay("failed:no-token")
            return
        }
        val ep = ensureEndpoint()
        val busy = activeCall() != null && _call.value?.phase != CallPhase.RINGING_IN
        if (busy) {
            noteRelay("failed:busy")
            Log.w(TAG, "push while a call is still ${_call.value?.phase}; leaving that call")
            return
        }
        // relayVoipPushNotification returns immediately when a previous leg is still active,
        // and then no Incoming is built. Reset those flags only when the UI has no call.
        if (ep.isCallRunning) {
            Log.w(TAG, "resetting stale SDK call before push")
            resetSdkLegs(ep)
        }
        creds = cfg.sipUsername to cfg.sipPassword
        loginReason = "push"
        wantOnline = true
        applyRegTimeout(ep)
        // loginForIncomingWithUsername is the supported push entry. It registers from the
        // data message and constructs Incoming. relayVoipPushNotification is deprecated and
        // refuses to run unless this process already logged in with a device token.
        handlingPush = true
        val ok = runCatching {
            ep.loginForIncomingWithUsername(cfg.sipUsername, cfg.sipPassword, token, "", java.util.HashMap(data))
        }.getOrElse {
            noteRelay("failed:${it.javaClass.simpleName}")
            false
        }
        handlingPush = false
        if (!ok) {
            if (lastRelayResult == "none" || lastRelayResult == "relogin") noteRelay("failed:login-incoming")
            else if (!lastRelayResult.startsWith("failed:")) noteRelay("failed:login-incoming")
            return
        }
        loggedInWithDeviceToken = true
        val inc = sdkIncoming()
        noteRelay(if (inc != null) "ok" else "ok:no-incoming")
        Log.i(TAG, "loginForIncomingWithUsername from=$from incoming=${inc != null} ${registrationSnapshot()}")
        showIncoming(from, inc)
    }

    private fun sdkIncoming(): Incoming? {
        val sc = controller() ?: return null
        return runCatching {
            SipController::class.java.getDeclaredField("incoming").apply { isAccessible = true }.get(sc) as? Incoming
        }.getOrNull()
    }

    /** Clears isActive on leftover legs so a push can construct a new Incoming. */
    private fun resetSdkLegs(ep: Endpoint) {
        val reset = runCatching {
            Class.forName("com.plivo.endpoint.IO").getDeclaredMethod("reset").apply { isAccessible = true }
        }.getOrNull() ?: return
        val legs = buildList {
            outgoing?.let { add(it) }
            incoming?.let { add(it) }
            runCatching {
                Endpoint::class.java.getDeclaredField("curOutgoing").apply { isAccessible = true }.get(ep)
            }.getOrNull()?.let { add(it) }
            sdkIncoming()?.let { add(it) }
        }
        legs.distinct().forEach { leg -> runCatching { reset.invoke(leg) } }
        incoming = null
        outgoing = null
    }

    private fun noteRelay(result: String) {
        lastRelayResult = result
        Log.i(TAG, "relay $result")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) publishLine() else main.post { publishLine() }
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
        val cfg = ConfigStore.config.value ?: return false
        if (activeCall() != null) return false
        val number = normalize(raw)
        if (number.isEmpty()) return false
        ensureEndpoint()
        sawSignalling = false
        _call.value = CallState(Direction.OUTGOING, number, CallPhase.DIALING)
        armWatchdog()
        Log.i(TAG, "dial $number ${registrationSnapshot()}")
        if (_registration.value == Registration.ONLINE && endpoint?.registered == true) {
            return placeOutgoing(number)
        }
        pendingDial = number
        login(cfg.sipUsername, cfg.sipPassword, deviceToken, reason = "outgoing")
        return true
    }

    private fun placeOutgoing(number: String): Boolean {
        val ep = endpoint ?: return false
        val out = ep.createOutgoingCall()
        if (out == null) {
            Log.w(TAG, "dial rejected by SDK for $number")
            finish(Outcome.FAILED, "Call failed")
            return false
        }
        outgoing = out
        Log.i(TAG, "placing $number sdkCallRunning=${sdkCallRunning()}")
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
        val rejected = runCatching { incoming?.reject() == true }.getOrDefault(false)
        if (!rejected) {
            runCatching { controller()?.reject() }
                .onFailure { Log.w(TAG, "reject failed: ${it.javaClass.simpleName}") }
        }
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
        if (!stayRegistered && pendingDial == null) {
            main.postDelayed({ releaseContact("call-ended") }, ENDED_HOLD_MS)
        }
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
        val contact = inc.fromContact
        val raw = if (!contact.isNullOrBlank()) contact else runCatching { inc.fromSip }.getOrNull().orEmpty()
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
        Log.i(TAG, "call hook installed")
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

private fun summarizeSignal(raw: String?): String {
    val text = raw.orEmpty()
    if (text.contains("From:", ignoreCase = true) || text.contains("Call-ID", ignoreCase = true)) {
        return describeInvite(text).summary()
    }
    val cleaned = text.replace(Regex("(?i)(password|authorization)\\s*[=:]\\s*\\S+"), "$1=<redacted>")
    return cleaned.take(160).ifBlank { "no-detail" }
}

/** First and last four characters. The full token is never logged or shown. */
internal fun maskToken(token: String?): String {
    if (token.isNullOrBlank()) return "none"
    if (token.length <= 8) return "present"
    return token.take(4) + "…" + token.takeLast(4)
}

private fun sipUser(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val body = value.substringAfter('<', value).substringBefore('>').trim()
    val user = body.removePrefix("sip:").removePrefix("sips:").removePrefix("tel:")
        .substringBefore('@').substringBefore(';').substringBefore(':').trim().trim('"')
    if (user.isBlank() || user.any { it.isWhitespace() } || user.length > 64) return null
    return user
}
