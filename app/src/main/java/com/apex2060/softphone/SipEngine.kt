package com.apex2060.softphone

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import com.apex2060.softphone.config.ConfigStore
import com.twentytwo.phone.sipspike.AddrOverride
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.Direction
import com.apex2060.softphone.history.Outcome
import com.apex2060.softphone.push.PushClient
import com.apex2060.softphone.push.PushGate
import com.apex2060.softphone.push.SipPushMatch
import com.apex2060.softphone.record.CallRecording
import com.apex2060.softphone.record.Recordings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.OnCallTsxStateParam
import org.pjsip.pjsua2.SipHeader
import org.pjsip.pjsua2.SipTxOption
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.OnTransportStateParam
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_transport_state
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_media_status
import org.pjsip.pjsua2.pjsua_ipv6_use
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

enum class Registration { ONLINE, CONNECTING, FAILED, OFFLINE }

enum class CallPhase { RINGING_IN, DIALING, RINGING_OUT, ACTIVE, ENDED }

data class CallUi(
    val direction: Direction,
    val number: String,
    val phase: CallPhase,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val answeredAt: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val endedLabel: String? = null,
    val outcome: Outcome? = null,
    val lineId: String = "",
    val lineLabel: String = "",
    val callerName: String? = null,
)

data class LineUi(
    val registration: Registration = Registration.OFFLINE,
    val status: String = "Idle",
    val network: String = "",
    val signature: String = "",
    val transport: String = "auto",
    val remembered: String = "none",
    val username: String = "",
    val manual: Boolean = false,
    val rtpIn: Long = 0,
    val rtpOut: Long = 0,
    val outbound: String = OutboundRoute.DIRECT.label,
    val nativeError: String? = null,
)

/**
 * One PJSIP endpoint, one account per phone that is on.
 * Each account uses the same registrar, NAT, and transport order as the single-line build.
 */
class SipEngine(private val app: Application) {
    val lines = mutableStateListOf<String>()
    private val _registration = MutableStateFlow(Registration.OFFLINE)
    val registration: StateFlow<Registration> = _registration.asStateFlow()
    private val _call = MutableStateFlow<CallUi?>(null)
    val call: StateFlow<CallUi?> = _call.asStateFlow()
    private val _statuses = MutableStateFlow<Map<String, LineUi>>(emptyMap())
    val statuses: StateFlow<Map<String, LineUi>> = _statuses.asStateFlow()
    private val _network = MutableStateFlow("")
    val network: StateFlow<String> = _network.asStateFlow()

    private val main = Handler(app.mainLooper)
    private val thread = HandlerThread("sipspike-pjsip")
    private val pjsip: Handler
    private val audio = app.getSystemService(AudioManager::class.java)
    private val foregroundCount = AtomicInteger(0)

    private val regs = LinkedHashMap<String, RegLine>()
    private var ep: SpikeEndpoint? = null
    private var logWriter: RedactingLog? = null
    private var nativeError: String? = null
    @Volatile private var callObj: SpikeCall? = null
    /** Strong refs so the GC cannot finalize a Call and hang it up. */
    private val liveCalls = ArrayList<SpikeCall>()
    private val loggedResponses = HashSet<String>()
    private var sawEarly = false
    private var triedClient = false
    private var audioMedia: AudioMedia? = null
    private val callRecording = CallRecording(app)
    val recordingActive: StateFlow<Boolean> = callRecording.active
    private var audioIndex = -1
    private var signaled = false
    private var uiCleared = false
    private var closingCall = false
    private var focus: AudioFocusRequest? = null
    private var rtpQuietLogs = 0
    private var lastRtpIn = -1L
    private var lastRtpOut = -1L
    private var pendingNet = "change"
    private var seenNetKey = ""
    @Volatile private var pushSession: PushWake? = null
    private var wakeGen = 0

    private class PushWake(
        val callUuid: String,
        val from: String,
        val fromName: String,
        val lineIds: Set<String>,
        val receivedAt: Long,
        val generation: Int,
        val readySent: MutableSet<String> = HashSet(),
        var inviteArrived: Boolean = false,
        var answered: Boolean = false,
    )

    fun isAwake(): Boolean = pushSession != null || regs.values.any { it.hold }

    private class RegLine(val id: String) {
        @Volatile var wantRegister = false
        @Volatile var powerToken = 0
        @Volatile var username = ""
        @Volatile var password = ""
        @Volatile var generation = 0
        @Volatile var registeredOk = false
        @Volatile var attempting = false
        var attemptToken = 0
        var plan: List<TransportMode> = emptyList()
        var planIndex = -1
        var planSig = ""
        var planHasIpv4 = true
        var currentMode: TransportMode? = null
        var sawAuth = false
        var closing = false
        var timeout: Runnable? = null
        var retry: Runnable? = null
        var account: SpikeAccount? = null
        @Volatile var pendingReregister = false
        /** Register for a push or an outbound call, then drop it when push is active. */
        @Volatile var hold = false
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNet("available")
        override fun onLost(network: Network) = scheduleNet("lost")
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) =
            scheduleNet("capabilities")
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
            scheduleNet("link")
    }

    init {
        thread.start()
        pjsip = Handler(thread.looper)
        SpikeLog.listener = { block ->
            main.post {
                for (line in block.lineSequence()) {
                    if (line.isBlank()) continue
                    lines.add(line)
                }
                val extra = lines.size - 400
                if (extra > 0) lines.removeRange(0, extra)
            }
        }
        nativeError = loadNative()
        if (nativeError != null) {
            SpikeLog.append("native load failed: $nativeError")
        }
    }

    fun start() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        try {
            cm.registerDefaultNetworkCallback(netCallback)
        } catch (e: RuntimeException) {
            SpikeLog.append("network callback: ${e.message}")
        }
        publishNet(NetInfo.snapshot(app))
        if (ConfigStore.anyEnabled()) ClientProbe.startOnce()
    }

    fun setOutboundRoute(lineId: String, route: OutboundRoute) {
        ConfigStore.setOutboundRoute(lineId, route)
        SpikeLog.append("outbound route ${route.label}")
        publishTransport(lineId)
    }

    fun onActivityStarted() { foregroundCount.incrementAndGet() }
    fun onActivityStopped() { foregroundCount.decrementAndGet() }
    fun isAppForeground(): Boolean = foregroundCount.get() > 0

    fun logText(): String = SpikeLog.text()
    fun clearLog() {
        SpikeLog.clear()
        main.post { lines.clear() }
    }

    /** Registers every phone that is on. Same credentials and an in-flight attempt are left alone. */
    fun syncEnabledLines() {
        pjsip.post { syncOnThread() }
    }

    /**
     * REGISTER with Expires 0 for this phone only, then drop its account.
     * Other phones keep their accounts.
     */
    fun turnOff(lineId: String) {
        pjsip.post { stopLine(lineId) }
    }

    fun refreshRegistration(reason: String) {
        pjsip.post {
            val key = netKey(NetInfo.snapshot(app))
            var acted = false
            for (reg in regs.values.toList()) {
                if (!reg.wantRegister) continue
                if (reason != "timer" && reg.registeredOk && ep != null && key == seenNetKey) continue
                acted = true
                SpikeLog.append("refresh $reason")
                if (reg.registeredOk && ep != null) {
                    try {
                        reg.account?.setRegistration(true)
                    } catch (e: Exception) {
                        SpikeLog.append("refresh failed: ${e.message}")
                    }
                } else if (!reg.attempting) {
                    beginChain(reg)
                }
            }
            if (!acted && regs.values.any { it.wantRegister }) {
                SpikeLog.append("refresh $reason skipped, network unchanged ($key)")
            }
        }
    }

    fun setTransportOverride(lineId: String, mode: TransportMode?) {
        ConfigStore.setTransportOverride(lineId, mode)
        SpikeLog.append("transport override ${mode?.label ?: "auto"}")
        publishTransport(lineId)
        pjsip.post {
            val reg = regs[lineId] ?: return@post
            if (reg.wantRegister) beginChain(reg)
        }
    }

    fun normalize(raw: String, lineId: String? = null): String {
        val code = lineId?.let { ConfigStore.line(it)?.config?.countryCode }
            ?: ConfigStore.themeLine()?.config?.countryCode
        return DialTarget.normalize(raw, code)
    }

    fun dial(raw: String, lineId: String): Boolean {
        val stored = ConfigStore.line(lineId) ?: return false
        if (!stored.enabled) return false
        if (_call.value != null && _call.value?.phase != CallPhase.ENDED) return false
        val number = normalize(raw, lineId)
        if (number.isEmpty()) return false
        ConfigStore.setLastDial(lineId)
        val ui = CallUi(
            direction = Direction.OUTGOING,
            number = number,
            phase = CallPhase.DIALING,
            lineId = lineId,
            lineLabel = stored.config.title,
        )
        val reg = regs[lineId]
        if (reg == null || !reg.registeredOk) {
            if (!PushGate.parked(lineId)) return false
            val creds = stored.resolvedCreds() ?: return false
            _call.value = ui
            signaled = false
            uiCleared = false
            loggedResponses.clear()
            sawEarly = false
            triedClient = false
            callRecording.reset()
            pjsip.post {
                val held = regs.getOrPut(lineId) { RegLine(lineId) }
                held.hold = true
                wakeGen++
                if (!held.wantRegister || (!held.attempting && !held.registeredOk)) {
                    startLine(held, creds.first, creds.second)
                }
            }
            return true
        }
        _call.value = ui
        signaled = false
        uiCleared = false
        loggedResponses.clear()
        sawEarly = false
        triedClient = false
        callRecording.reset()
        val route = stored.route()
        SpikeLog.append(
            "dial digits=$number country=${stored.config.countryCode ?: "none"} " +
                "allowFreeDial=${stored.config.allowFreeDial} route=${route.label}"
        )
        pjsip.post { placeCall(ui, viaClient = route == OutboundRoute.VIA_CLIENT) }
        return true
    }

    fun answer() {
        if (_call.value?.phase != CallPhase.RINGING_IN) return
        SpikeLog.append("answer tapped")
        pjsip.post {
            val current = callObj
            if (current == null) {
                SpikeLog.append("answer failed: call object was dropped")
                return@post
            }
            prepareAudio("answer")
            try {
                val prm = CallOpParam(true)
                prm.statusCode = 200
                prm.opt.audioCount = 1
                SpikeLog.append("answer 200 audioCount=${prm.opt.audioCount}")
                current.answer(prm)
            } catch (e: Exception) {
                SpikeLog.append("answer failed: ${e.message}\n${trace(e)}")
            }
        }
    }

    fun decline() {
        if (_call.value?.phase != CallPhase.RINGING_IN) return
        SpikeLog.append("hangup party=local-user decline")
        pjsip.post { finish(fromRemote = false, outcome = Outcome.DECLINED, label = "Declined", code = 486) }
    }

    fun hangup() {
        val state = _call.value ?: return
        if (state.phase == CallPhase.ENDED) return
        if (state.phase == CallPhase.RINGING_IN && state.answeredAt == null) {
            decline()
            return
        }
        val outcome = if (state.answeredAt != null) Outcome.COMPLETED else Outcome.FAILED
        SpikeLog.append("hangup party=local-user phase=${state.phase}")
        pjsip.post { finish(fromRemote = false, outcome = outcome, label = "Call ended", code = 0) }
    }

    fun toggleMute() {
        pjsip.post {
            val ui = _call.value ?: return@post
            val mute = !ui.muted
            try {
                val am = audioMedia
                val cap = ep?.audDevManager()?.captureDevMedia
                if (am != null && cap != null) {
                    if (mute) cap.stopTransmit(am) else cap.startTransmit(am)
                }
            } catch (e: Exception) {
                SpikeLog.append("mute failed: ${e.message}")
            }
            _call.value = ui.copy(muted = mute)
            SpikeLog.append("mute=$mute")
        }
    }

    fun toggleSpeaker() {
        val ui = _call.value ?: return
        val on = !ui.speaker
        applySpeaker(on)
        _call.value = ui.copy(speaker = on)
        SpikeLog.append("speaker=$on")
    }

    fun sendDigit(digit: Char) {
        val d = digit.toString()
        pjsip.post {
            try {
                callObj?.dialDtmf(d)
                SpikeLog.append("DTMF $d")
            } catch (e: Exception) {
                SpikeLog.append("DTMF failed: ${e.message}")
            }
        }
    }

    private fun syncOnThread() {
        val wanted = ConfigStore.lines.value.mapNotNull { line ->
            if (!line.enabled) return@mapNotNull null
            val creds = line.resolvedCreds() ?: return@mapNotNull null
            line.id to creds
        }
        val ids = wanted.map { it.first }.toSet()
        if (wanted.isNotEmpty()) ClientProbe.startOnce()
        for ((id, creds) in wanted) {
            val reg = regs.getOrPut(id) { RegLine(id) }
            val busy = callOn(id) || (_call.value?.lineId == id && _call.value?.phase == CallPhase.DIALING)
            if (PushGate.parked(id) && !reg.hold && !busy) {
                if (reg.wantRegister || reg.account != null) stopLine(id)
                continue
            }
            if (reg.wantRegister && reg.username == creds.first && reg.password == creds.second &&
                (reg.registeredOk || reg.attempting)
            ) {
                continue
            }
            startLine(reg, creds.first, creds.second)
        }
        for (id in regs.keys.toList()) {
            if (id !in ids) stopLine(id)
        }
    }

    private fun startLine(reg: RegLine, user: String, pass: String) {
        if (nativeError != null) {
            setLineReg(reg.id, Registration.FAILED, "Native library failed to load")
            return
        }
        reg.powerToken++
        SpikeLog.noteSecret(pass)
        ConfigStore.line(reg.id)?.ticketKey?.let { SpikeLog.noteSecret(it) }
        reg.username = user.trim()
        reg.password = pass
        reg.wantRegister = true
        SpikeLog.append("login ${reg.username}")
        setLineReg(reg.id, Registration.CONNECTING, "Connecting…")
        beginChain(reg)
    }

    private fun stopLine(lineId: String) {
        val reg = regs[lineId]
        if (reg == null || (!reg.wantRegister && reg.account == null && !reg.attempting)) {
            setLineReg(lineId, Registration.OFFLINE, "Off")
            maybeDestroyEndpoint()
            return
        }
        reg.wantRegister = false
        val token = ++reg.powerToken
        cancelRetry(reg)
        reg.generation++
        reg.attemptToken++
        cancelTimeout(reg)
        val wait = reg.account != null && reg.registeredOk
        if (wait) {
            try {
                reg.account?.setRegistration(false)
                SpikeLog.append("unregister sent (Expires 0)")
            } catch (e: Exception) {
                SpikeLog.append("unregister failed: ${e.message}")
            }
        } else {
            SpikeLog.append("phone off, not registered")
        }
        val finish = Runnable {
            if (token != reg.powerToken || reg.wantRegister) return@Runnable
            if (callOn(lineId)) tearDownActiveCall("local-stack-teardown")
            dropAccount(reg)
            reg.registeredOk = false
            reg.attempting = false
            reg.currentMode = null
            clearPinIfUnused()
            maybeDestroyEndpoint()
            setLineReg(lineId, Registration.OFFLINE, "Off")
        }
        if (wait) pjsip.postDelayed(finish, 2_000) else finish.run()
    }

    private fun placeCall(ui: CallUi, viaClient: Boolean) {
        val reg = regs[ui.lineId]
        val acc = reg?.account
        if (reg == null || acc == null || !reg.registeredOk) {
            SpikeLog.append("INVITE blocked: not registered")
            main.post { _call.value = null }
            return
        }
        var next: SpikeCall? = null
        try {
            signaled = false
            uiCleared = false
            prepareAudio("dial")
            next = SpikeCall(acc, this, incoming = false)
            retain(next)
            if (viaClient) triedClient = true
            val tls = viaClient || reg.currentMode != TransportMode.UDP
            val target = DialTarget.requestUri(ui.number, tls)
            val prm = CallOpParam(true)
            prm.opt.audioCount = 1
            if (viaClient) {
                val tx = SipTxOption()
                val route = SipHeader()
                route.hName = "Route"
                route.hValue = "<${DialTarget.clientRoute()}>"
                tx.headers.add(route)
                tx.localUri = "sip:${reg.username}@${DialTarget.DOMAIN}"
                prm.txOption = tx
                if (reg.currentMode == TransportMode.UDP) {
                    SpikeLog.append("via client.plivo.com uses TLS; this registration is UDP")
                }
            }
            next.makeCall(target, prm)
            SpikeLog.append(
                "INVITE $target audioCount=${prm.opt.audioCount} " +
                    "from=sip:${reg.username}@${DialTarget.DOMAIN} " +
                    if (viaClient) "route=<${DialTarget.clientRoute()}>" else "route=none"
            )
        } catch (e: Exception) {
            SpikeLog.append("INVITE failed: ${e.message}\n${trace(e)}")
            next?.let { failed ->
                if (callObj === failed) callObj = null
                liveCalls.remove(failed)
                try { failed.delete() } catch (_: Exception) {}
            }
            main.post {
                _call.value = ui.copy(phase = CallPhase.ENDED, endedLabel = "Call failed", outcome = Outcome.FAILED)
            }
            main.postDelayed({ if (_call.value?.startedAt == ui.startedAt) _call.value = null }, 600)
        }
    }

    private fun beginChain(reg: RegLine) {
        if (!reg.wantRegister || reg.username.isEmpty() || reg.password.isEmpty()) return
        SpikeLog.noteSecret(reg.password)
        val gen = ++reg.generation
        reg.attemptToken++
        cancelTimeout(reg)
        cancelRetry(reg)
        if (callOn(reg.id)) tearDownActiveCall("local-stack-teardown")
        dropAccount(reg)
        reg.registeredOk = false
        reg.attempting = true
        reg.sawAuth = false
        val net = NetInfo.snapshot(app)
        publishNet(net)
        SpikeLog.append(net.detail)
        logDns("phone.plivo.com")
        logDns(DialTarget.PROXY_HOST)
        logDns("mobile.phone.plivo.com")
        reg.planSig = TransportPlan.signature(net.summary, net.hasIpv4)
        seenNetKey = TransportPlan.networkKey(reg.planSig, net.addresses)
        reg.planHasIpv4 = net.hasIpv4
        val stored = ConfigStore.line(reg.id)
        val remembered = stored?.winner(reg.planSig)
        val override = stored?.transportMode()
        reg.plan = TransportPlan.attempts(reg.planSig, remembered, override)
        reg.planIndex = -1
        SpikeLog.append(
            "network signature=${reg.planSig} remembered=${remembered?.label ?: "none"} " +
                "override=${override?.label ?: "auto"}"
        )
        SpikeLog.append("transport plan " + reg.plan.joinToString(" -> ") { it.label })
        publishTransport(reg.id)
        setLineReg(reg.id, Registration.CONNECTING, "Connecting…")
        advance(reg, gen)
    }

    private fun advance(reg: RegLine, gen: Int) {
        if (gen != reg.generation || !reg.wantRegister) return
        reg.planIndex++
        if (reg.planIndex >= reg.plan.size) {
            reg.attempting = false
            val override = ConfigStore.line(reg.id)?.transportMode() != null
            val why = if (override) "Override transport failed" else "All transports failed"
            setLineReg(reg.id, Registration.FAILED, why)
            val waiting = _call.value
            if (reg.hold && waiting != null && waiting.lineId == reg.id && waiting.phase == CallPhase.DIALING && callObj == null) {
                SpikeLog.append("$why")
                reg.hold = false
                main.post {
                    _call.value = waiting.copy(phase = CallPhase.ENDED, endedLabel = "Call failed", outcome = Outcome.FAILED)
                }
                main.postDelayed({ if (_call.value?.startedAt == waiting.startedAt) _call.value = null }, 600)
                if (PushGate.parked(reg.id)) stopLine(reg.id)
                return
            }
            SpikeLog.append("$why; retry in 30s")
            scheduleRetry(reg, gen)
            return
        }
        val mode = reg.plan[reg.planIndex]
        val token = ++reg.attemptToken
        reg.currentMode = mode
        reg.sawAuth = false
        SpikeLog.append("transport attempt ${reg.planIndex + 1}/${reg.plan.size} ${mode.label}")
        publishTransport(reg.id)
        try {
            startOne(reg, mode, reg.password)
            armTimeout(reg, gen, token, mode)
        } catch (e: Exception) {
            SpikeLog.append("transport setup failed ${mode.label}: ${e.message}")
            dropAccount(reg)
            if (token == reg.attemptToken) advance(reg, gen)
        }
    }

    /**
     * The endpoint and its UDP, TLS, and IPv6 TLS transports are created once.
     * A later account does not tear them down.
     */
    private fun ensureEndpoint() {
        if (ep != null) return
        val writer = RedactingLog()
        val endpoint = SpikeEndpoint(this)
        try {
            endpoint.libCreate()
            val cfg = EpConfig()
            cfg.logConfig.level = 5
            cfg.logConfig.consoleLevel = 5
            cfg.logConfig.writer = writer
            writer.swigReleaseOwnership()
            cfg.uaConfig.userAgent = "22SIPTest/${BuildConfig.VERSION_NAME}"
            cfg.uaConfig.natTypeInSdp = 1
            cfg.medConfig.clockRate = 16000
            cfg.medConfig.sndClockRate = 16000
            cfg.medConfig.noVad = true
            endpoint.libInit(cfg)
            val ca = caFile()
            createTransport(endpoint, pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, ca, tls = true)
            createTransport(endpoint, pjsip_transport_type_e.PJSIP_TRANSPORT_TLS6, ca, tls = true)
            createTransport(endpoint, pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, ca, tls = false)
            endpoint.libStart()
            tuneCodecs(endpoint)
        } catch (e: Exception) {
            try { endpoint.libDestroy() } catch (_: Exception) {}
            try { endpoint.delete() } catch (_: Exception) {}
            throw e
        }
        logWriter = writer
        ep = endpoint
    }

    private fun createTransport(endpoint: Endpoint, type: Int, ca: File, tls: Boolean) {
        try {
            val tp = TransportConfig()
            tp.port = 0
            if (tls) {
                tp.tlsConfig.verifyServer = true
                tp.tlsConfig.caListFile = ca.absolutePath
                tp.tlsConfig.msecTimeout = 15_000
            }
            endpoint.transportCreate(type, tp)
            SpikeLog.append("transport created type=$type")
        } catch (e: Exception) {
            SpikeLog.append("transport create failed type=$type: ${e.message}")
        }
    }

    private fun startOne(reg: RegLine, mode: TransportMode, password: String) {
        val net = NetInfo.snapshot(app)
        applyPin(reg, mode)
        ensureEndpoint()
        val hasV4 = net.hasIpv4 || reg.planHasIpv4
        val mediaIpv6 = if (mode == TransportMode.TLS_IPV6 && !hasV4) {
            pjsua_ipv6_use.PJSUA_IPV6_ENABLED_USE_IPV6_ONLY
        } else {
            pjsua_ipv6_use.PJSUA_IPV6_ENABLED_PREFER_IPV4
        }
        val sipIpv6 = when (mode) {
            TransportMode.TLS_IPV6 -> pjsua_ipv6_use.PJSUA_IPV6_ENABLED_USE_IPV6_ONLY
            else -> pjsua_ipv6_use.PJSUA_IPV6_DISABLED
        }
        SpikeLog.append(
            "sip ipv6=$sipIpv6 (${ipv6Name(sipIpv6)}) media ipv6=$mediaIpv6 (${ipv6Name(mediaIpv6)}) " +
                "srtp=disabled ice=off"
        )

        val accCfg = AccountConfig()
        val tls = mode != TransportMode.UDP
        accCfg.idUri = "sip:${reg.username}@${DialTarget.DOMAIN}"
        accCfg.regConfig.registrarUri = DialTarget.registrarUri(tls)
        accCfg.regConfig.timeoutSec = 120
        accCfg.regConfig.retryIntervalSec = 300
        accCfg.sipConfig.authCreds.add(AuthCredInfo("digest", "*", reg.username, 0, password))
        accCfg.sipConfig.ipv6Use = sipIpv6
        accCfg.natConfig.iceEnabled = false
        accCfg.natConfig.contactRewriteUse = 1
        accCfg.natConfig.contactRewriteMethod = 2 or 4
        accCfg.natConfig.viaRewriteUse = 1
        accCfg.natConfig.sdpNatRewriteUse = 1
        accCfg.natConfig.sipOutboundUse = 1
        accCfg.natConfig.udpKaIntervalSec = 15
        accCfg.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
        accCfg.mediaConfig.srtpSecureSignaling = 0
        accCfg.mediaConfig.ipv6Use = mediaIpv6
        accCfg.mediaConfig.streamKaEnabled = true
        SpikeLog.append(
            "nat contactRewrite=1 method=6 viaRewrite=1 sdpNatRewrite=1 " +
                "sipOutbound=1 udpKa=15s tlsKa=default natTypeInSdp=1 (no STUN, so no learned NAT type) " +
                "streamKa=compiled-out vad=off"
        )
        val acc = SpikeAccount(this, reg.id, reg.generation)
        acc.create(accCfg, true)
        reg.account = acc
        SpikeLog.append(
            "account created registrar=${accCfg.regConfig.registrarUri} proxy=none from=${accCfg.idUri}"
        )
    }

    /**
     * The DNS pin is process-wide. A phone on a different transport must not
     * retarget the host another phone is already registered through.
     */
    private fun applyPin(reg: RegLine, mode: TransportMode) {
        val others = regs.values.filter { other ->
            other !== reg && other.account != null && (other.registeredOk || other.attempting)
        }
        if (mode == TransportMode.TLS_IPV6) {
            if (others.any { it.currentMode != null && it.currentMode != TransportMode.TLS_IPV6 }) {
                throw IllegalStateException("IPv6 pin skipped; another phone is on a different transport")
            }
            val v6 = resolveAaaa()
            if (!AddrOverride.set("phone.plivo.com", v6)) {
                throw IllegalStateException("Could not pin phone.plivo.com to $v6")
            }
            SpikeLog.append(
                "pinned phone.plivo.com -> $v6 (AAAA of mobile.phone.plivo.com); TLS name phone.plivo.com"
            )
        } else {
            if (others.any { it.currentMode == TransportMode.TLS_IPV6 }) {
                throw IllegalStateException("DNS clear skipped; another phone is using the IPv6 pin")
            }
            AddrOverride.set(null, null)
            SpikeLog.append("DNS override cleared")
        }
    }

    private fun win(reg: RegLine, mode: TransportMode) {
        if (reg.registeredOk) return
        reg.registeredOk = true
        reg.attempting = false
        cancelTimeout(reg)
        cancelRetry(reg)
        ConfigStore.remember(reg.id, reg.planSig, mode)
        SpikeLog.append("transport won ${mode.label} on ${reg.planSig}")
        setLineReg(reg.id, Registration.ONLINE, "Registered")
        publishTransport(reg.id)
        val session = pushSession
        if (session != null && reg.id in session.lineIds && session.readySent.add(reg.id)) {
            val waited = System.currentTimeMillis() - session.receivedAt
            SpikeLog.append("push register latency ms=$waited code=200")
            main.post { PushClient.notifyReady(app, reg.id, session.callUuid) }
        }
        val waiting = _call.value
        if (reg.hold && waiting != null && waiting.lineId == reg.id &&
            waiting.phase == CallPhase.DIALING && callObj == null
        ) {
            val route = ConfigStore.line(reg.id)?.route() ?: OutboundRoute.DIRECT
            placeCall(waiting, viaClient = route == OutboundRoute.VIA_CLIENT)
        }
    }

    private fun failTransport(reg: RegLine, gen: Int, token: Int, why: String) {
        if (gen != reg.generation || token != reg.attemptToken) return
        reg.attemptToken++
        cancelTimeout(reg)
        val override = ConfigStore.line(reg.id)?.transportMode() != null
        if (reg.registeredOk && !override) {
            SpikeLog.append("registered transport failed: $why")
            reg.registeredOk = false
            ConfigStore.forget(reg.id, reg.planSig)
            beginChain(reg)
            return
        }
        SpikeLog.append("transport failed ${reg.currentMode?.label ?: "?"}: $why")
        if (callOn(reg.id)) tearDownActiveCall("local-stack-teardown")
        dropAccount(reg)
        if (override) {
            reg.attempting = false
            setLineReg(reg.id, Registration.FAILED, why)
            scheduleRetry(reg, gen)
            return
        }
        advance(reg, gen)
    }

    private fun dropAccount(reg: RegLine) {
        reg.closing = true
        try { reg.account?.shutdown() } catch (_: Exception) {}
        try { reg.account?.delete() } catch (_: Exception) {}
        reg.account = null
        reg.closing = false
    }

    private fun endpointBusy(): Boolean =
        regs.values.any { it.wantRegister || it.attempting || it.account != null }

    private fun maybeDestroyEndpoint() {
        if (endpointBusy()) return
        val endpoint = ep ?: return
        ep = null
        try { endpoint.libDestroy() } catch (e: Exception) {
            SpikeLog.append("libDestroy: ${e.message}")
        }
        try { endpoint.delete() } catch (_: Exception) {}
        logWriter = null
        try { AddrOverride.set(null, null) } catch (_: UnsatisfiedLinkError) {}
    }

    private fun clearPinIfUnused() {
        val needed = regs.values.any {
            it.currentMode == TransportMode.TLS_IPV6 && (it.registeredOk || it.attempting || it.account != null)
        }
        if (!needed) {
            try { AddrOverride.set(null, null) } catch (_: UnsatisfiedLinkError) {}
        }
    }

    private fun armTimeout(reg: RegLine, gen: Int, token: Int, mode: TransportMode) {
        val r = Runnable {
            pjsip.post {
                if (gen == reg.generation && token == reg.attemptToken && !reg.registeredOk) {
                    failTransport(reg, gen, token, "timeout 20s ${mode.label}")
                }
            }
        }
        reg.timeout = r
        main.postDelayed(r, 20_000)
    }

    private fun cancelTimeout(reg: RegLine) {
        reg.timeout?.let { main.removeCallbacks(it) }
        reg.timeout = null
    }

    private fun scheduleRetry(reg: RegLine, gen: Int) {
        cancelRetry(reg)
        val r = Runnable {
            pjsip.post {
                if (gen == reg.generation && reg.wantRegister && !reg.registeredOk) beginChain(reg)
            }
        }
        reg.retry = r
        main.postDelayed(r, 30_000)
    }

    private fun cancelRetry(reg: RegLine) {
        reg.retry?.let { main.removeCallbacks(it) }
        reg.retry = null
    }

    private fun logDns(host: String) {
        try {
            val all = InetAddress.getAllByName(host)
            if (all.isEmpty()) {
                SpikeLog.append("dns $host (no records)")
                return
            }
            val text = all.joinToString("; ") { addr ->
                val kind = if (addr is Inet6Address) "AAAA" else "A"
                "$kind ${addr.hostAddress}"
            }
            SpikeLog.append("dns $host $text")
        } catch (e: Exception) {
            SpikeLog.append("dns $host failed: ${e.message}")
        }
    }

    private fun resolveAaaa(): String {
        val all = InetAddress.getAllByName("mobile.phone.plivo.com").toList()
        val v6 = all.filterIsInstance<Inet6Address>().firstOrNull { !it.isLinkLocalAddress }
            ?: throw IllegalStateException("mobile.phone.plivo.com has no global AAAA on this network")
        return v6.hostAddress?.substringBefore('%')
            ?: throw IllegalStateException("AAAA had no host address")
    }

    private fun tuneCodecs(endpoint: Endpoint) {
        val codecs = endpoint.codecEnum2()
        val offered = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        var sawEvent = false
        for (i in 0 until codecs.size) {
            val id = codecs.get(i).codecId ?: continue
            val priority = CodecPlan.priority(id)
            if (priority == CodecPlan.TELEPHONE_EVENT) sawEvent = true
            try {
                endpoint.codecSetPriority(id, priority.toShort())
            } catch (e: Exception) {
                SpikeLog.append("codec $id priority failed: ${e.message}")
                continue
            }
            if (priority > 0) {
                val vad = disableVad(endpoint, id)
                offered += id
                SpikeLog.append("codec $id priority=$priority vad=$vad")
            } else {
                disabled += id
                SpikeLog.append("codec $id priority=0 disabled")
            }
        }
        if (!sawEvent) {
            SpikeLog.append(
                "rfc2833 telephone-event/8000 pt=120 is added with PCMU/PCMA; " +
                    "it is not in the codec list (id would be telephone-event/8000/1)"
            )
        }
        SpikeLog.append(
            "offered codecs ${offered.joinToString().ifBlank { "(none)" }}; " +
                "disabled ${disabled.joinToString().ifBlank { "(none)" }}"
        )
    }

    private fun disableVad(endpoint: Endpoint, id: String): String = try {
        val param = endpoint.codecGetParam(id)
        param.setting.vad = false
        endpoint.codecSetParam(id, param)
        "off"
    } catch (e: Exception) {
        "unchanged (${e.message})"
    }

    private fun ipv6Name(value: Int): String = when (value) {
        pjsua_ipv6_use.PJSUA_IPV6_DISABLED -> "disabled"
        pjsua_ipv6_use.PJSUA_IPV6_ENABLED_PREFER_IPV4 -> "prefer-ipv4"
        pjsua_ipv6_use.PJSUA_IPV6_ENABLED_PREFER_IPV6 -> "prefer-ipv6"
        pjsua_ipv6_use.PJSUA_IPV6_ENABLED_USE_IPV6_ONLY -> "ipv6-only"
        else -> "no-preference"
    }

    private fun callOn(lineId: String): Boolean {
        val ui = _call.value
        return callObj != null && ui != null && ui.lineId == lineId && ui.phase != CallPhase.ENDED
    }

    private fun tearDownActiveCall(reason: String) {
        if (closingCall) return
        val recorded = callRecording.stop()
        stopRtp()
        val dropped = _call.value
        if (callObj != null && dropped != null && dropped.phase != CallPhase.ENDED) {
            SpikeLog.append("hangup party=$reason")
        }
        closingCall = true
        try { callObj?.hangup(CallOpParam()) } catch (e: Exception) {
            SpikeLog.append("stack teardown hangup failed: ${e.message}")
        }
        if (dropped != null && dropped.phase != CallPhase.ENDED) {
            val duration = dropped.answeredAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
            val historyId = CallHistory.add(
                number = dropped.number,
                direction = dropped.direction,
                outcome = if (dropped.answeredAt != null) Outcome.COMPLETED else Outcome.FAILED,
                startedAt = dropped.startedAt,
                durationSec = duration,
                limit = historyLimit(dropped.lineId),
                lineId = dropped.lineId,
                lineLabel = dropped.lineLabel,
            )
            offerRecording(recorded, historyId, dropped.number, dropped.startedAt)
            uiCleared = true
            main.post { if (_call.value?.startedAt == dropped.startedAt) _call.value = null }
        } else if (recorded != null && dropped != null) {
            val row = CallHistory.records.value.firstOrNull {
                it.startedAt == dropped.startedAt && it.number == dropped.number && it.recording == null
            }
            if (row != null) offerRecording(recorded, row.id, row.number, row.startedAt)
            else recorded.delete()
        }
        val dying = callObj
        callObj = null
        liveCalls.remove(dying)
        try { dying?.delete() } catch (_: Exception) {}
        audioMedia = null
        abandonAudio()
        closingCall = false
    }

    private fun scheduleNet(reason: String) {
        pendingNet = reason
        main.removeCallbacks(netTick)
        main.postDelayed(netTick, 800)
    }

    private val netTick = Runnable {
        val reason = pendingNet
        val snap = NetInfo.snapshot(app)
        publishNet(snap)
        val sig = TransportPlan.signature(snap.summary, snap.hasIpv4)
        val key = TransportPlan.networkKey(sig, snap.addresses)
        if (key == seenNetKey) {
            SpikeLog.append("network $reason unchanged ($key), skip re-register")
            return@Runnable
        }
        val previous = seenNetKey
        seenNetKey = key
        SpikeLog.append("network $reason\n${snap.detail}")
        val active = regs.values.filter { it.wantRegister }
        if (active.isEmpty()) return@Runnable
        if (callInProgress()) {
            active.forEach { it.pendingReregister = true }
            SpikeLog.append("network $reason changed ($previous -> $key) deferred until the call ends")
            return@Runnable
        }
        pjsip.post { reregisterAfterNetwork(reason, sig, key, previous) }
    }

    private fun reregisterAfterNetwork(reason: String, sig: String, key: String, previous: String) {
        var ipChangeDone = false
        for (reg in regs.values.toList()) {
            if (!reg.wantRegister) continue
            when {
                reg.planSig.isNotEmpty() && sig != reg.planSig -> {
                    SpikeLog.append("re-register after $reason signature $previous -> $key")
                    beginChain(reg)
                }
                reg.registeredOk -> {
                    if (!ipChangeDone) {
                        try {
                            ep?.handleIpChange(IpChangeParam())
                        } catch (e: Exception) {
                            SpikeLog.append("ip change failed: ${e.message}")
                        }
                        ipChangeDone = true
                    }
                    try {
                        reg.account?.setRegistration(true)
                        SpikeLog.append("re-register after $reason ($key)")
                    } catch (e: Exception) {
                        SpikeLog.append("re-register failed: ${e.message}\n${trace(e)}")
                    }
                }
                !reg.attempting -> beginChain(reg)
                else -> SpikeLog.append("network $reason during attempt, not restarting")
            }
        }
    }

    private fun callInProgress(): Boolean {
        val phase = _call.value?.phase
        return callObj != null && phase != null && phase != CallPhase.ENDED
    }

    private fun netKey(snap: NetSnapshot): String =
        TransportPlan.networkKey(TransportPlan.signature(snap.summary, snap.hasIpv4), snap.addresses)

    private fun onReg(lineId: String, accountGen: Int, code: Int, reason: String, expiration: Long, status: Int) {
        val reg = regs[lineId] ?: return
        if (reg.closing) return
        SpikeLog.append("REGISTER $code $reason exp=$expiration status=$status")
        if (accountGen != reg.generation || !reg.wantRegister) return
        val mode = reg.currentMode ?: return
        when {
            code in 200..299 -> win(reg, mode)
            code == 401 || code == 407 -> {
                if (reg.sawAuth) failTransport(reg, reg.generation, reg.attemptToken, "REGISTER $code $reason")
                else {
                    reg.sawAuth = true
                    SpikeLog.append("auth challenge, waiting for authenticated REGISTER")
                }
            }
            code >= 300 -> failTransport(reg, reg.generation, reg.attemptToken, "REGISTER $code $reason")
        }
    }

    fun wakeIncoming(callUuid: String, from: String, fromName: String, lineIds: Set<String>) {
        if (pushSession?.callUuid.equals(callUuid, ignoreCase = true)) {
            SpikeLog.append("push ignore duplicate")
            return
        }
        val generation = ++wakeGen
        val session = PushWake(
            callUuid = callUuid,
            from = from,
            fromName = fromName,
            lineIds = lineIds,
            receivedAt = System.currentTimeMillis(),
            generation = generation,
        )
        pushSession = session
        val line = ConfigStore.line(lineIds.first())
        val shown = from.ifBlank { "Incoming" }
        _call.value = CallUi(
            direction = Direction.INCOMING,
            number = shown,
            phase = CallPhase.RINGING_IN,
            lineId = line?.id ?: lineIds.first(),
            lineLabel = line?.config?.title.orEmpty(),
            callerName = fromName.ifBlank { null },
        )
        pjsip.post {
            if (pushSession?.generation != generation) return@post
            for (id in lineIds) {
                val stored = ConfigStore.line(id) ?: continue
                if (!stored.enabled) continue
                val creds = stored.resolvedCreds() ?: continue
                val reg = regs.getOrPut(id) { RegLine(id) }
                reg.hold = true
                if (reg.registeredOk) {
                    if (session.readySent.add(id)) {
                        SpikeLog.append("push register latency ms=0 code=200")
                        main.post { PushClient.notifyReady(app, id, callUuid) }
                    }
                } else if (!reg.attempting) {
                    startLine(reg, creds.first, creds.second)
                }
            }
        }
        main.postDelayed({
            if (pushSession?.generation != generation) return@postDelayed
            if (pushSession?.inviteArrived == true) return@postDelayed
            SpikeLog.append("push timeout no invite")
            val current = _call.value
            if (current != null && current.phase == CallPhase.RINGING_IN && current.answeredAt == null) {
                _call.value = current.copy(phase = CallPhase.ENDED, endedLabel = "Missed call", outcome = Outcome.MISSED)
                main.postDelayed({ if (_call.value?.startedAt == current.startedAt) _call.value = null }, 400)
            }
            pjsip.post { releaseWake(current?.lineId.orEmpty(), generation) }
        }, 20_000)
    }

    fun cancelIncoming(callUuid: String) {
        val session = pushSession ?: return
        if (!session.callUuid.equals(callUuid, ignoreCase = true)) return
        if (session.answered || _call.value?.answeredAt != null) return
        SpikeLog.append("push cancel applied")
        val current = _call.value
        if (session.inviteArrived && current?.phase == CallPhase.RINGING_IN) {
            decline()
            return
        }
        if (current != null && current.phase == CallPhase.RINGING_IN) {
            _call.value = current.copy(phase = CallPhase.ENDED, endedLabel = "Missed call", outcome = Outcome.MISSED)
            main.postDelayed({ if (_call.value?.startedAt == current.startedAt) _call.value = null }, 400)
        }
        val generation = session.generation
        pushSession = null
        pjsip.post { releaseWake(current?.lineId.orEmpty(), generation) }
    }

    private fun releaseWake(lineId: String, generation: Int) {
        if (generation != wakeGen && pushSession?.generation != generation) return
        if (pushSession?.generation == generation) pushSession = null
        val ids = if (lineId.isBlank()) regs.keys.toList() else listOf(lineId)
        for (id in ids) {
            val reg = regs[id] ?: continue
            if (!reg.hold) continue
            if (_call.value?.phase?.let { it != CallPhase.ENDED } == true &&
                _call.value?.lineId == id && callObj != null
            ) {
                continue
            }
            reg.hold = false
            if (PushGate.parked(id)) stopLine(id)
        }
        main.post { com.apex2060.softphone.phone.PhoneService.considerIdle(app) }
    }

    private fun onIncoming(acc: SpikeAccount, callId: Int, sipMessage: String?) {
        val reg = regs[acc.lineId]
        if (reg == null || !reg.wantRegister) {
            SpikeLog.append("incoming ignored, phone is off")
            try {
                val gone = SpikeCall(acc, callId, this, incoming = true)
                val prm = CallOpParam()
                prm.statusCode = 480
                gone.hangup(prm)
                pjsip.post { try { gone.delete() } catch (_: Exception) {} }
            } catch (e: Exception) {
                SpikeLog.append("off reject failed: ${e.message}")
            }
            return
        }
        val headerUuid = SipPushMatch.uuidFromInvite(sipMessage)
        val session = pushSession
        val match = if (session == null) {
            SipPushMatch.Match.NONE
        } else {
            SipPushMatch.matches(session.callUuid, session.from, headerUuid, SipPushMatch.fromUser(sipMessage))
        }
        val armed = session != null && !session.inviteArrived && acc.lineId in session.lineIds
        if (!armed && _call.value != null && _call.value?.phase != CallPhase.ENDED) {
            SpikeLog.append("incoming busy, 486")
            try {
                val busy = SpikeCall(acc, callId, this, incoming = true)
                val prm = CallOpParam()
                prm.statusCode = 486
                busy.hangup(prm)
                pjsip.post { try { busy.delete() } catch (_: Exception) {} }
            } catch (e: Exception) {
                SpikeLog.append("busy reject failed: ${e.message}")
            }
            return
        }
        try {
            val next = SpikeCall(acc, callId, this, incoming = true)
            retain(next)
            loggedResponses.clear()
            val ringing = CallOpParam()
            ringing.statusCode = 180
            next.answer(ringing)
            signaled = false
            uiCleared = false
            callRecording.reset()
            val from = displayNumber(runCatching { next.info.remoteUri }.getOrDefault(""))
            val label = ConfigStore.line(acc.lineId)?.config?.title.orEmpty()
            if (armed) {
                session.inviteArrived = true
                val waited = System.currentTimeMillis() - session.receivedAt
                SpikeLog.append("invite arrive match=${match.name.lowercase()} latencyMs=$waited")
            }
            SpikeLog.append("INVITE incoming from $from")
            val ui = CallUi(
                direction = Direction.INCOMING,
                number = from.ifBlank { session?.from.orEmpty() },
                phase = CallPhase.RINGING_IN,
                lineId = acc.lineId,
                lineLabel = label,
                callerName = session?.fromName?.ifBlank { null },
            )
            main.post { _call.value = ui }
        } catch (e: Exception) {
            SpikeLog.append("incoming failed: ${e.message}\n${trace(e)}")
        }
    }

    private fun retain(call: SpikeCall) {
        liveCalls.add(call)
        callObj = call
    }

    private fun onCallState(current: SpikeCall) {
        if (closingCall) return
        val info = try { current.info } catch (e: Exception) {
            SpikeLog.append("call state: ${e.message}")
            return
        }
        SpikeLog.append(
            "call ${info.stateText} code=${info.lastStatusCode} ${info.lastReason} " +
                "localHangup=$signaled ${info.remoteUri}"
        )
        if (info.state != pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED &&
            info.state != pjsip_inv_state.PJSIP_INV_STATE_NULL
        ) {
            logSdpDump(current)
        }
        val ui = _call.value
        if (!current.incoming && (
                info.state == pjsip_inv_state.PJSIP_INV_STATE_EARLY ||
                    info.state == pjsip_inv_state.PJSIP_INV_STATE_CONNECTING ||
                    info.state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED
                )
        ) {
            sawEarly = true
        }
        when (info.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                val number = ui?.number ?: displayNumber(info.remoteUri)
                val started = ui?.startedAt ?: System.currentTimeMillis()
                main.post {
                    _call.value = (ui ?: CallUi(Direction.OUTGOING, number, CallPhase.ACTIVE, startedAt = started))
                        .copy(
                            phase = CallPhase.ACTIVE,
                            number = number,
                            answeredAt = ui?.answeredAt ?: System.currentTimeMillis(),
                        )
                }
                engageAudio(speaker = ui?.speaker == true)
                startRtp()
                pushSession?.let { it.answered = true }
                callRecording.markConfirmed()
                callRecording.startIfReady(audioMedia, captureMedia())
            }
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                val route = ui?.lineId?.let { ConfigStore.line(it)?.route() } ?: OutboundRoute.DIRECT
                if (ui != null && DialTarget.shouldRetryViaClient(
                        route,
                        info.lastStatusCode,
                        sawEarly,
                        triedClient,
                        ClientProbe.answers,
                    )
                ) {
                    triedClient = true
                    SpikeLog.append(
                        "direct rejected ${info.lastStatusCode} ${info.lastReason}; " +
                            "one INVITE via client.plivo.com"
                    )
                    scheduleDelete(current)
                    pjsip.post { placeCall(ui, viaClient = true) }
                    return
                }
                if (!current.incoming && !triedClient && !sawEarly && info.lastStatusCode >= 300 &&
                    route == OutboundRoute.DIRECT && !ClientProbe.answers
                ) {
                    SpikeLog.append(
                        "direct rejected ${info.lastStatusCode} ${info.lastReason}; " +
                            "client.plivo.com probe has no SIP answer, no retry (${ClientProbe.text.value})"
                    )
                }
                val outcome = when {
                    ui?.phase == CallPhase.RINGING_IN && ui.answeredAt == null && !signaled -> Outcome.MISSED
                    ui?.outcome != null -> ui.outcome
                    ui?.answeredAt != null -> Outcome.COMPLETED
                    else -> Outcome.FAILED
                }
                val label = when (outcome) {
                    Outcome.MISSED -> "Missed call"
                    Outcome.DECLINED -> "Declined"
                    Outcome.COMPLETED -> "Call ended"
                    Outcome.FAILED -> "Call ended ${info.lastStatusCode}"
                }
                val who = if (signaled) "local-app" else "pjsua-or-remote"
                SpikeLog.append(
                    "hangup party=$who code=${info.lastStatusCode} ${info.lastReason} " +
                        "state=${info.stateText}"
                )
                finish(fromRemote = true, outcome = outcome, label = label, code = info.lastStatusCode)
            }
            pjsip_inv_state.PJSIP_INV_STATE_CALLING ->
                updatePhase(CallPhase.DIALING, info.remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_INCOMING ->
                updatePhase(CallPhase.RINGING_IN, info.remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_EARLY,
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> {
                val phase = if (current.incoming) CallPhase.RINGING_IN else CallPhase.RINGING_OUT
                updatePhase(phase, info.remoteUri)
            }
        }
    }

    private fun updatePhase(phase: CallPhase, remoteUri: String) {
        val ui = _call.value ?: return
        if (ui.phase == CallPhase.ENDED || ui.phase == CallPhase.ACTIVE) return
        main.post { _call.value = ui.copy(phase = phase, number = ui.number.ifBlank { displayNumber(remoteUri) }) }
    }

    private fun onCallTsx(prm: OnCallTsxStateParam) {
        if (closingCall) return
        try {
            val ev = prm.e ?: return
            val tsx = ev.body?.tsxState ?: return
            val src = tsx.src
            val sent = src?.tdata?.wholeMsg
            if (!sent.isNullOrBlank() && sent.trimStart().startsWith("INVITE ", ignoreCase = true)) {
                val callId = headerValue(sent, "Call-ID")
                val cseq = headerValue(sent, "CSeq")
                val authed = sent.contains("Authorization:", ignoreCase = true)
                if (loggedResponses.add("tx $callId $cseq auth=$authed")) {
                    SpikeLog.append("outbound INVITE\n${clipSip(sent)}")
                }
            }
            val got = src?.rdata?.wholeMsg
            if (!got.isNullOrBlank()) {
                val line = got.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val callId = headerValue(got, "Call-ID")
                val key = "$callId ${line.substringBefore(';').take(80)}"
                if (key.isNotEmpty() && loggedResponses.add(key)) {
                    val code = tsx.tsx?.statusCode ?: 0
                    SpikeLog.append("sip response $line code=$code")
                    if (code == 401 || code == 407 || code >= 300) {
                        SpikeLog.append("sip response\n${clipSip(got)}")
                    }
                    if (code == 401 || code == 407) {
                        SpikeLog.append("digest challenge; pjsua answers with the account credentials")
                    }
                }
            }
        } catch (e: Exception) {
            SpikeLog.append("tsx log failed: ${e.message}")
        }
    }

    private fun headerValue(message: String, name: String): String =
        message.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.take(80).orEmpty()

    private fun clipSip(message: String): String {
        val text = message.replace("\r\n", "\n").trim()
        return if (text.length <= 6000) text else text.take(6000) + "\n…truncated"
    }

    private fun onCallMedia(current: SpikeCall) {
        if (closingCall) return
        val info = try { current.info } catch (_: Exception) { return }
        val endpoint = ep ?: return
        val media = info.media
        for (i in 0 until media.size) {
            val item = media.get(i)
            if (item.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
            val statusName = mediaStatusName(item.status)
            val active = item.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE ||
                item.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD
            if (item.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ERROR) {
                SpikeLog.append("media idx=$i status=error (pjsua, not a local hangup)")
            }
            try {
                val stream = current.getStreamInfo(i.toLong())
                SpikeLog.append(
                    "sdp audio idx=$i codec=${stream.codecName}/${stream.codecClockRate} " +
                        "dir=${stream.dir} remote=${stream.remoteRtpAddress} status=$statusName"
                )
            } catch (e: Exception) {
                SpikeLog.append("sdp audio idx=$i status=$statusName (${e.message})")
            }
            if (!active) continue
            try {
                val am = current.getAudioMedia(i)
                val mgr = endpoint.audDevManager()
                if (_call.value?.muted != true) mgr.captureDevMedia.startTransmit(am)
                am.startTransmit(mgr.playbackDevMedia)
                audioMedia = am
                audioIndex = i
                SpikeLog.append("audio connected")
                callRecording.startIfReady(am, mgr.captureDevMedia)
                logSdpDump(current)
            } catch (e: Exception) {
                SpikeLog.append("audio connect failed: ${e.message}\n${trace(e)}")
            }
        }
    }

    private fun logSdpDump(current: SpikeCall) {
        val dump = try { current.dump(true, "") } catch (_: Exception) { return }
        val rows = dump.lineSequence().map { it.trim() }.filter { line ->
            line.startsWith("m=") || line.startsWith("c=") ||
                line.startsWith("a=rtpmap") || line.startsWith("a=send") || line.startsWith("a=inactive")
        }.take(30).joinToString("\n")
        if (rows.isNotEmpty()) SpikeLog.append("sdp\n$rows")
    }

    private fun onTransport(prm: OnTransportStateParam) {
        val state = when (prm.state) {
            pjsip_transport_state.PJSIP_TP_STATE_CONNECTED -> "connected"
            pjsip_transport_state.PJSIP_TP_STATE_DISCONNECTED -> "disconnected"
            pjsip_transport_state.PJSIP_TP_STATE_SHUTDOWN -> "shutdown"
            pjsip_transport_state.PJSIP_TP_STATE_DESTROY -> "destroy"
            else -> prm.state.toString()
        }
        val tls = prm.tlsInfo
        if (tls != null && !tls.isEmpty) {
            val msgs = buildString {
                val vec = tls.verifyMsgs
                for (i in 0 until vec.size) {
                    if (isNotEmpty()) append("; ")
                    append(vec.get(i))
                }
            }
            SpikeLog.append(
                "transport $state type=${prm.type} err=${prm.lastError} " +
                    "local=${tls.localAddr} remote=${tls.remoteAddr} " +
                    "cipher=${tls.cipherName} verify=${tls.verifyStatus} $msgs"
            )
        } else {
            SpikeLog.append("transport $state type=${prm.type} err=${prm.lastError}")
        }
        if (closingCall || prm.state != pjsip_transport_state.PJSIP_TP_STATE_DISCONNECTED) return
        val mode = modeOfTransport(prm.type) ?: return
        val err = prm.lastError
        val victims = regs.values.filter { it.attempting && !it.registeredOk && !it.closing && it.currentMode == mode }
        for (reg in victims) {
            val gen = reg.generation
            val token = reg.attemptToken
            pjsip.post { failTransport(reg, gen, token, "transport disconnected err=$err") }
        }
    }

    /** pjsua reports the transport name ("TLS", "TLS6", "UDP"), not the enum. */
    private fun modeOfTransport(type: String?): TransportMode? = when (type?.trim()?.uppercase()) {
        "TLS6" -> TransportMode.TLS_IPV6
        "TLS" -> TransportMode.TLS_IPV4
        "UDP" -> TransportMode.UDP
        else -> null
    }

    private fun finish(fromRemote: Boolean, outcome: Outcome, label: String, code: Int) {
        val recorded = callRecording.stop()
        val current = callObj
        if (!fromRemote && !signaled && current != null) {
            signaled = true
            try {
                if (code == 486) {
                    val prm = CallOpParam()
                    prm.statusCode = 486
                    SpikeLog.append("signal 486 before teardown")
                    current.hangup(prm)
                } else {
                    SpikeLog.append("signal BYE/CANCEL before teardown")
                    current.hangup(CallOpParam())
                }
            } catch (e: Exception) {
                SpikeLog.append("hangup signal failed: ${e.message}\n${trace(e)}")
            }
        }
        val ui = _call.value
        if (uiCleared || ui == null) {
            if (recorded != null) {
                val row = ui?.let { call ->
                    CallHistory.records.value.firstOrNull {
                        it.recording == null && it.startedAt == call.startedAt && it.number == call.number
                    }
                }
                if (row != null) offerRecording(recorded, row.id, row.number, row.startedAt)
                else recorded.delete()
            }
            if (fromRemote) scheduleDelete(current)
            return
        }
        uiCleared = true
        stopRtp()
        val duration = ui.answeredAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
        val historyId = if (ui.phase != CallPhase.ENDED) {
            CallHistory.add(
                number = ui.number,
                direction = ui.direction,
                outcome = outcome,
                startedAt = ui.startedAt,
                durationSec = duration,
                limit = historyLimit(ui.lineId),
                lineId = ui.lineId,
                lineLabel = ui.lineLabel,
            )
        } else {
            -1L
        }
        offerRecording(recorded, historyId, ui.number, ui.startedAt)
        abandonAudio()
        audioMedia = null
        val ended = ui.copy(phase = CallPhase.ENDED, endedLabel = label, outcome = outcome)
        main.post { _call.value = ended }
        SpikeLog.append("call teardown outcome=$outcome after signal code=$code")
        main.postDelayed({
            if (_call.value?.startedAt == ui.startedAt) _call.value = null
        }, 400)
        scheduleDelete(current)
        val endedLine = ui.lineId
        val endedGen = wakeGen
        pjsip.post { releaseWake(endedLine, endedGen) }
        val deferred = regs.values.filter { it.pendingReregister && it.wantRegister }
        if (deferred.isNotEmpty()) {
            pjsip.post {
                if (callObj != null) return@post
                for (reg in deferred) {
                    if (!reg.pendingReregister || !reg.wantRegister) continue
                    reg.pendingReregister = false
                    SpikeLog.append("re-register deferred network change now that the call ended")
                    beginChain(reg)
                }
            }
        }
    }

    private fun historyLimit(lineId: String): Int =
        ConfigStore.line(lineId)?.config?.historyLimit ?: ConfigStore.themeLine()?.config?.historyLimit ?: 200

    private fun offerRecording(file: File?, historyId: Long, number: String, startedAt: Long) {
        if (file == null) return
        if (historyId <= 0) {
            file.delete()
            SpikeLog.append("recording dropped, no history row")
            return
        }
        main.post { Recordings.offer(app, historyId, number, startedAt, file, isAppForeground()) }
    }

    private fun captureMedia(): AudioMedia? = try {
        ep?.audDevManager()?.captureDevMedia
    } catch (_: Exception) {
        null
    }

    private fun scheduleDelete(current: SpikeCall?) {
        if (current == null) return
        pjsip.post {
            try {
                current.delete()
            } catch (e: Exception) {
                SpikeLog.append("call delete failed: ${e.message}")
            }
            if (callObj === current) callObj = null
            liveCalls.remove(current)
        }
    }

    private val rtpTick = object : Runnable {
        override fun run() {
            pjsip.post { sampleRtp() }
            if (_call.value?.phase == CallPhase.ACTIVE) main.postDelayed(this, 2_000)
        }
    }

    private fun startRtp() {
        rtpQuietLogs = 0
        lastRtpIn = -1
        lastRtpOut = -1
        main.removeCallbacks(rtpTick)
        main.postDelayed(rtpTick, 2_000)
    }

    private fun stopRtp() {
        main.removeCallbacks(rtpTick)
    }

    private fun sampleRtp() {
        val current = callObj ?: return
        if (audioIndex < 0) return
        try {
            val stat = current.getStreamStat(audioIndex.toLong()).rtcp
            val inn = stat.rxStat.pkt
            val out = stat.txStat.pkt
            val lineId = _call.value?.lineId
            if (lineId != null) {
                main.post {
                    val cur = _statuses.value[lineId] ?: return@post
                    _statuses.value = _statuses.value + (lineId to cur.copy(rtpIn = inn, rtpOut = out))
                }
            }
            if (inn != lastRtpIn || out != lastRtpOut) {
                SpikeLog.append("rtp in=$inn out=$out")
                lastRtpIn = inn
                lastRtpOut = out
                rtpQuietLogs = 0
            } else if (inn == 0L && out == 0L) {
                rtpQuietLogs++
                if (rtpQuietLogs == 3) SpikeLog.append("rtp still 0/0 (silent audio?)")
            }
        } catch (e: Exception) {
            SpikeLog.append("rtp stat: ${e.message}")
        }
    }

    private fun applySpeaker(on: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
    }

    private fun prepareAudio(why: String) {
        val mic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val dev = try {
            val mgr = ep?.audDevManager()
            "capture=${mgr?.captureDev} playback=${mgr?.playbackDev}"
        } catch (e: Exception) {
            "dev unavailable (${e.message})"
        }
        SpikeLog.append("audio prepare $why micGranted=$mic mode=${audio.mode} $dev")
        if (!mic) SpikeLog.append("audio RECORD_AUDIO is not granted; capture will fail")
        engageAudio(_call.value?.speaker == true)
    }

    private fun mediaStatusName(status: Int): String = when (status) {
        pjsua_call_media_status.PJSUA_CALL_MEDIA_NONE -> "none"
        pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE -> "active"
        pjsua_call_media_status.PJSUA_CALL_MEDIA_LOCAL_HOLD -> "local-hold"
        pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD -> "remote-hold"
        pjsua_call_media_status.PJSUA_CALL_MEDIA_ERROR -> "error"
        else -> status.toString()
    }

    private fun trace(e: Throwable): String =
        Log.getStackTraceString(e).lineSequence().take(6).joinToString(" | ")

    private fun engageAudio(speaker: Boolean) {
        applySpeaker(speaker)
        if (Build.VERSION.SDK_INT >= 26 && focus == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focus = req
            audio.requestAudioFocus(req)
        }
    }

    private fun abandonAudio() {
        audio.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun caFile(): File {
        val out = File(app.filesDir, "cacert.pem")
        if (!out.exists() || out.length() < 1000) {
            app.assets.open("cacert.pem").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    private fun loadNative(): String? = try {
        System.loadLibrary("c++_shared")
        System.loadLibrary("pjsua2")
        null
    } catch (t: UnsatisfiedLinkError) {
        t.message ?: t.toString()
    }

    private fun publishNet(snap: NetSnapshot) {
        main.post { _network.value = snap.summary }
    }

    private fun publishTransport(id: String) {
        val reg = regs[id]
        val stored = ConfigStore.line(id)
        val mode = reg?.currentMode
        val remembered = reg?.planSig?.let { stored?.winner(it) }
        val override = stored?.transportMode()
        val label = override?.uiLabel ?: mode?.uiLabel ?: "auto"
        main.post {
            val cur = _statuses.value[id] ?: LineUi()
            _statuses.value = _statuses.value + (id to cur.copy(
                transport = label,
                remembered = remembered?.uiLabel ?: "none",
                signature = reg?.planSig?.ifBlank { cur.signature } ?: cur.signature,
                username = reg?.username?.ifBlank { cur.username } ?: cur.username,
                manual = stored?.manualCreds() != null,
                outbound = stored?.route()?.uiLabel ?: cur.outbound,
                network = _network.value.ifBlank { cur.network },
            ))
        }
    }

    private fun setLineReg(id: String, registration: Registration, status: String) {
        val reg = regs[id]
        val stored = ConfigStore.line(id)
        main.post {
            val cur = _statuses.value[id] ?: LineUi()
            _statuses.value = _statuses.value + (id to cur.copy(
                registration = registration,
                status = status,
                username = reg?.username?.ifBlank { stored?.resolvedCreds()?.first.orEmpty() } ?: cur.username,
                manual = stored?.manualCreds() != null,
                nativeError = nativeError,
                network = _network.value.ifBlank { cur.network },
                outbound = stored?.route()?.uiLabel ?: cur.outbound,
            ))
            publishSummary()
        }
    }

    private fun publishSummary() {
        val enabled = ConfigStore.lines.value.filter { it.enabled }.map { it.id }.toSet()
        val states = _statuses.value.filterKeys { it in enabled }.values.map { it.registration }
        _registration.value = when {
            states.any { it == Registration.ONLINE } -> Registration.ONLINE
            states.any { it == Registration.CONNECTING } -> Registration.CONNECTING
            states.any { it == Registration.FAILED } -> Registration.FAILED
            else -> Registration.OFFLINE
        }
    }

    private fun displayNumber(uri: String): String {
        val user = Regex("(?i)sip:([^@>;\\s]+)").find(uri)?.groupValues?.get(1)
        return user?.trim().orEmpty().ifBlank { uri.trim() }
    }

    private inner class RedactingLog : LogWriter() {
        override fun write(entry: LogEntry) {
            val msg = entry.msg ?: return
            SpikeLog.append(msg)
        }
    }

    private class SpikeEndpoint(private val engine: SipEngine) : Endpoint() {
        override fun onTransportState(prm: OnTransportStateParam) {
            engine.onTransport(prm)
        }
    }

    private class SpikeAccount(
        private val engine: SipEngine,
        val lineId: String,
        private val accountGen: Int,
    ) : Account() {
        override fun onRegState(prm: OnRegStateParam) {
            val code = prm.code
            val reason = prm.reason ?: ""
            val expiration = prm.expiration
            val status = prm.status
            engine.pjsip.post { engine.onReg(lineId, accountGen, code, reason, expiration, status) }
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            val message = runCatching { prm.rdata?.wholeMsg }.getOrNull()
            engine.onIncoming(this, prm.callId, message)
        }
    }

    private class SpikeCall : Call {
        private val engine: SipEngine
        val incoming: Boolean

        constructor(acc: Account, engine: SipEngine, incoming: Boolean) : super(acc) {
            this.engine = engine
            this.incoming = incoming
        }

        constructor(acc: Account, callId: Int, engine: SipEngine, incoming: Boolean) : super(acc, callId) {
            this.engine = engine
            this.incoming = incoming
        }

        override fun onCallState(prm: OnCallStateParam) {
            engine.onCallState(this)
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            engine.onCallMedia(this)
        }

        override fun onCallTsxState(prm: OnCallTsxStateParam) {
            if (!incoming) engine.onCallTsx(prm)
        }
    }
}
