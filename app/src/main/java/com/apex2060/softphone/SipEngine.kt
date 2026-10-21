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
 * PJSIP registration and calls. Transport order comes from [TransportPlan].
 * A local hangup sends BYE or CANCEL before the UI call is cleared.
 */
class SipEngine(private val app: Application) {
    val lines = mutableStateListOf<String>()
    private val _registration = MutableStateFlow(Registration.OFFLINE)
    val registration: StateFlow<Registration> = _registration.asStateFlow()
    private val _call = MutableStateFlow<CallUi?>(null)
    val call: StateFlow<CallUi?> = _call.asStateFlow()
    private val _line = MutableStateFlow(LineUi())
    val line: StateFlow<LineUi> = _line.asStateFlow()

    private val main = Handler(app.mainLooper)
    private val thread = HandlerThread("sipspike-pjsip")
    private val pjsip: Handler
    private val audio = app.getSystemService(AudioManager::class.java)
    private val foregroundCount = AtomicInteger(0)

    @Volatile private var wantRegister = false
    @Volatile private var powerToken = 0
    @Volatile private var username = ""
    @Volatile private var generation = 0
    private var attemptToken = 0
    private var plan = emptyList<TransportMode>()
    private var planIndex = -1
    private var planSig = ""
    private var planHasIpv4 = true
    private var currentMode: TransportMode? = null
    private var sawAuth = false
    private var registeredOk = false
    private var attempting = false
    private var closing = false
    private var timeout: Runnable? = null
    private var retry: Runnable? = null

    private var ep: SpikeEndpoint? = null
    private var account: SpikeAccount? = null
    private var logWriter: RedactingLog? = null
    @Volatile private var callObj: SpikeCall? = null
    /** Strong refs so the GC cannot finalize a Call and hang it up. */
    private val liveCalls = ArrayList<SpikeCall>()
    @Volatile private var pendingReregister = false
    private val loggedResponses = HashSet<String>()
    private var sawEarly = false
    private var triedClient = false
    private var audioMedia: AudioMedia? = null
    private val callRecording = CallRecording(app)
    private var audioIndex = -1
    private var signaled = false
    private var uiCleared = false
    private var focus: AudioFocusRequest? = null
    private var rtpQuietLogs = 0
    private var lastRtpIn = -1L
    private var lastRtpOut = -1L
    private var pendingNet = "change"
    private var seenNetKey = ""

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
        val nativeError = loadNative()
        if (nativeError != null) {
            _line.value = LineUi(status = "Native library failed to load", nativeError = nativeError)
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
        if (ConfigStore.phoneEnabled.value) ClientProbe.startOnce()
    }

    fun setOutboundRoute(route: OutboundRoute) {
        ConfigStore.setOutboundRoute(route)
        SpikeLog.append("outbound route ${route.label}")
        _line.value = _line.value.copy(outbound = route.uiLabel)
    }

    fun onActivityStarted() { foregroundCount.incrementAndGet() }
    fun onActivityStopped() { foregroundCount.decrementAndGet() }
    fun isAppForeground(): Boolean = foregroundCount.get() > 0

    fun logText(): String = SpikeLog.text()
    fun clearLog() {
        SpikeLog.clear()
        main.post { lines.clear() }
    }

    fun login(user: String, pass: String) {
        if (!ConfigStore.phoneEnabled.value) {
            wantRegister = false
            SpikeLog.append("login skipped, phone is off")
            setReg(Registration.OFFLINE, "Phone is off")
            return
        }
        powerToken++
        ClientProbe.startOnce()
        if (_line.value.nativeError != null) {
            setReg(Registration.FAILED, "Native library failed to load")
            return
        }
        SpikeLog.noteSecret(pass)
        ConfigStore.ticket?.key?.let { SpikeLog.noteSecret(it) }
        username = user.trim()
        wantRegister = true
        setReg(Registration.CONNECTING, "Connecting…")
        pjsip.post { beginChain() }
    }

    /**
     * REGISTER with Expires 0, then tear the stack down. The register plan
     * itself is unchanged; this only stops it.
     */
    fun turnOff() {
        wantRegister = false
        val token = ++powerToken
        cancelRetry()
        pjsip.post {
            if (token != powerToken || wantRegister) return@post
            generation++
            attemptToken++
            cancelTimeout()
            val acc = account
            val wait = acc != null && registeredOk
            if (wait) {
                try {
                    acc?.setRegistration(false)
                    SpikeLog.append("unregister sent (Expires 0)")
                } catch (e: Exception) {
                    SpikeLog.append("unregister failed: ${e.message}")
                }
            } else {
                SpikeLog.append("phone off, not registered")
            }
            val finish = Runnable {
                if (token != powerToken || wantRegister) return@Runnable
                destroyStack()
                registeredOk = false
                attempting = false
                main.post {
                    _registration.value = Registration.OFFLINE
                    _call.value = null
                    _line.value = _line.value.copy(
                        registration = Registration.OFFLINE,
                        status = "Phone is off",
                    )
                }
            }
            if (wait) pjsip.postDelayed(finish, 2_000) else finish.run()
        }
    }

    fun logout() {
        wantRegister = false
        powerToken++
        cancelRetry()
        pjsip.post {
            generation++
            attemptToken++
            cancelTimeout()
            destroyStack()
            main.post {
                _registration.value = Registration.OFFLINE
                _call.value = null
                _line.value = _line.value.copy(registration = Registration.OFFLINE, status = "Offline", username = "")
            }
        }
    }

    fun refreshRegistration(reason: String) {
        pjsip.post {
            if (!wantRegister) return@post
            val key = netKey(NetInfo.snapshot(app))
            if (reason != "timer" && registeredOk && ep != null && key == seenNetKey) {
                SpikeLog.append("refresh $reason skipped, network unchanged ($key)")
                return@post
            }
            SpikeLog.append("refresh $reason")
            if (registeredOk && ep != null) {
                try {
                    account?.setRegistration(true)
                } catch (e: Exception) {
                    SpikeLog.append("refresh failed: ${e.message}")
                }
            } else if (!attempting) {
                beginChain()
            }
        }
    }

    fun setTransportOverride(mode: TransportMode?) {
        ConfigStore.setTransportOverride(mode)
        SpikeLog.append("transport override ${mode?.label ?: "auto"}")
        publishTransport()
        if (wantRegister) pjsip.post { beginChain() }
    }

    fun normalize(raw: String): String =
        DialTarget.normalize(raw, ConfigStore.config.value?.countryCode)

    fun dial(raw: String): Boolean {
        if (_registration.value != Registration.ONLINE) return false
        if (_call.value != null && _call.value?.phase != CallPhase.ENDED) return false
        val number = normalize(raw)
        if (number.isEmpty()) return false
        val ui = CallUi(Direction.OUTGOING, number, CallPhase.DIALING)
        _call.value = ui
        signaled = false
        uiCleared = false
        loggedResponses.clear()
        sawEarly = false
        triedClient = false
        callRecording.reset()
        val cfg = ConfigStore.config.value
        val route = ConfigStore.outboundRoute()
        SpikeLog.append(
            "dial digits=$number country=${cfg?.countryCode ?: "none"} " +
                "allowFreeDial=${cfg?.allowFreeDial != false} route=${route.label}"
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
                // true fills audioCount=1. The no-arg constructor leaves it 0 and
                // pjsua then omits the audio answer.
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

    private fun placeCall(ui: CallUi, viaClient: Boolean) {
        val acc = account
        if (acc == null || !registeredOk) {
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
            val tls = viaClient || currentMode != TransportMode.UDP
            val target = DialTarget.requestUri(ui.number, tls)
            val prm = CallOpParam(true)
            prm.opt.audioCount = 1
            if (viaClient) {
                val tx = SipTxOption()
                val route = SipHeader()
                route.hName = "Route"
                route.hValue = "<${DialTarget.clientRoute()}>"
                tx.headers.add(route)
                tx.localUri = "sip:$username@${DialTarget.DOMAIN}"
                prm.txOption = tx
                if (currentMode == TransportMode.UDP) {
                    SpikeLog.append("via client.plivo.com uses TLS; this registration is UDP")
                }
            }
            next.makeCall(target, prm)
            SpikeLog.append(
                "INVITE $target audioCount=${prm.opt.audioCount} " +
                    "from=sip:$username@${DialTarget.DOMAIN} " +
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

    private fun beginChain() {
        if (!wantRegister || username.isEmpty()) return
        val pass = ConfigStore.resolvedCreds()?.second ?: return
        SpikeLog.noteSecret(pass)
        val gen = ++generation
        attemptToken++
        cancelTimeout()
        cancelRetry()
        destroyStack()
        registeredOk = false
        attempting = true
        sawAuth = false
        val net = NetInfo.snapshot(app)
        publishNet(net)
        SpikeLog.append(net.detail)
        logDns("phone.plivo.com")
        logDns(DialTarget.PROXY_HOST)
        logDns("mobile.phone.plivo.com")
        planSig = TransportPlan.signature(net.summary, net.hasIpv4)
        seenNetKey = TransportPlan.networkKey(planSig, net.addresses)
        planHasIpv4 = net.hasIpv4
        val remembered = ConfigStore.winner(planSig)
        val override = ConfigStore.transportOverride()
        plan = TransportPlan.attempts(planSig, remembered, override)
        planIndex = -1
        SpikeLog.append(
            "network signature=$planSig remembered=${remembered?.label ?: "none"} " +
                "override=${override?.label ?: "auto"}"
        )
        SpikeLog.append("transport plan " + plan.joinToString(" -> ") { it.label })
        publishTransport()
        setReg(Registration.CONNECTING, "Connecting…")
        advance(gen)
    }

    private fun advance(gen: Int) {
        if (gen != generation || !wantRegister) return
        planIndex++
        if (planIndex >= plan.size) {
            attempting = false
            val override = ConfigStore.transportOverride() != null
            val why = if (override) "Override transport failed" else "All transports failed"
            setReg(Registration.FAILED, why)
            SpikeLog.append("$why; retry in 30s")
            scheduleRetry(gen)
            return
        }
        val mode = plan[planIndex]
        val token = ++attemptToken
        currentMode = mode
        sawAuth = false
        SpikeLog.append("transport attempt ${planIndex + 1}/${plan.size} ${mode.label}")
        publishTransport()
        try {
            startOne(mode, pass())
            armTimeout(gen, token, mode)
        } catch (e: Exception) {
            SpikeLog.append("transport setup failed ${mode.label}: ${e.message}")
            destroyStack()
            if (token == attemptToken) advance(gen)
        }
    }

    private fun pass(): String = ConfigStore.resolvedCreds()?.second.orEmpty()

    private fun startOne(mode: TransportMode, password: String) {
        val net = NetInfo.snapshot(app)
        val ca = caFile()
        if (mode == TransportMode.TLS_IPV6) {
            val v6 = resolveAaaa()
            if (!AddrOverride.set("phone.plivo.com", v6)) {
                throw IllegalStateException("Could not pin phone.plivo.com to $v6")
            }
            SpikeLog.append("pinned phone.plivo.com -> $v6 (AAAA of mobile.phone.plivo.com); TLS name phone.plivo.com")
        } else {
            AddrOverride.set(null, null)
            SpikeLog.append("DNS override cleared")
        }

        val writer = RedactingLog()
        logWriter = writer
        val endpoint = SpikeEndpoint(this)
        ep = endpoint
        endpoint.libCreate()
        val cfg = EpConfig()
        cfg.logConfig.level = 5
        cfg.logConfig.consoleLevel = 5
        cfg.logConfig.writer = writer
        writer.swigReleaseOwnership()
        cfg.uaConfig.userAgent = "22SIPTest/${BuildConfig.VERSION_NAME}"
        // Default is already 1. Without a STUN server this never learns a NAT type,
        // so it does not add a useful a=X-nat line. Contact and SDP rewrite do the work.
        cfg.uaConfig.natTypeInSdp = 1
        cfg.medConfig.clockRate = 16000
        cfg.medConfig.sndClockRate = 16000
        cfg.medConfig.noVad = true
        endpoint.libInit(cfg)

        val tp = TransportConfig()
        tp.port = 0
        if (mode != TransportMode.UDP) {
            tp.tlsConfig.verifyServer = true
            tp.tlsConfig.caListFile = ca.absolutePath
            tp.tlsConfig.msecTimeout = 15_000
        }
        val type = when (mode) {
            TransportMode.TLS_IPV4 -> pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
            TransportMode.TLS_IPV6 -> pjsip_transport_type_e.PJSIP_TRANSPORT_TLS6
            TransportMode.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
        }
        endpoint.transportCreate(type, tp)
        endpoint.libStart()
        tuneCodecs(endpoint)

        val hasV4 = net.hasIpv4 || planHasIpv4
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
        accCfg.idUri = "sip:$username@${DialTarget.DOMAIN}"
        accCfg.regConfig.registrarUri = DialTarget.registrarUri(tls)
        accCfg.regConfig.timeoutSec = 120
        accCfg.regConfig.retryIntervalSec = 300
        accCfg.sipConfig.authCreds.add(AuthCredInfo("digest", "*", username, 0, password))
        accCfg.sipConfig.ipv6Use = sipIpv6
        accCfg.natConfig.iceEnabled = false
        accCfg.natConfig.contactRewriteUse = 1
        // 2 = rewrite Contact without unregistering first, 4 = always update when Via changes.
        // The extra REGISTER after a 401 (public address learned from Via) is this, and it
        // is what makes the Contact reachable from behind CGNAT.
        accCfg.natConfig.contactRewriteMethod = 2 or 4
        accCfg.natConfig.viaRewriteUse = 1
        accCfg.natConfig.sdpNatRewriteUse = 1
        accCfg.natConfig.sipOutboundUse = 1
        // Account keepalive is UDP-only unless the registrar returns Flow-Timer.
        // TLS CRLF keepalive is the transport default (about 90s) and stays on.
        accCfg.natConfig.udpKaIntervalSec = 15
        accCfg.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
        accCfg.mediaConfig.srtpSecureSignaling = 0
        accCfg.mediaConfig.ipv6Use = mediaIpv6
        // This libpjsua2.so is built with PJMEDIA_STREAM_ENABLE_KA=0, so pjsua2
        // drops the flag. VAD off on G.711 is what keeps RTP flowing in silence.
        accCfg.mediaConfig.streamKaEnabled = true
        SpikeLog.append(
            "nat contactRewrite=1 method=6 viaRewrite=1 sdpNatRewrite=1 " +
                "sipOutbound=1 udpKa=15s tlsKa=default natTypeInSdp=1 (no STUN, so no learned NAT type) " +
                "streamKa=compiled-out vad=off"
        )
        val acc = SpikeAccount(this)
        acc.create(accCfg, true)
        account = acc
        SpikeLog.append(
            "account created registrar=${accCfg.regConfig.registrarUri} proxy=none from=${accCfg.idUri}"
        )
    }

    private fun win(mode: TransportMode) {
        if (registeredOk && _registration.value == Registration.ONLINE) return
        registeredOk = true
        attempting = false
        cancelTimeout()
        cancelRetry()
        ConfigStore.remember(planSig, mode)
        SpikeLog.append("transport won ${mode.label} on $planSig")
        setReg(Registration.ONLINE, "Registered")
        publishTransport()
    }

    private fun failTransport(gen: Int, token: Int, why: String) {
        if (gen != generation || token != attemptToken) return
        attemptToken++
        cancelTimeout()
        if (registeredOk && ConfigStore.transportOverride() == null) {
            SpikeLog.append("registered transport failed: $why")
            registeredOk = false
            ConfigStore.forget(planSig)
            beginChain()
            return
        }
        SpikeLog.append("transport failed ${currentMode?.label ?: "?"}: $why")
        destroyStack()
        if (ConfigStore.transportOverride() != null) {
            attempting = false
            setReg(Registration.FAILED, why)
            scheduleRetry(gen)
            return
        }
        advance(gen)
    }

    private fun armTimeout(gen: Int, token: Int, mode: TransportMode) {
        val r = Runnable {
            pjsip.post {
                if (gen == generation && token == attemptToken && !registeredOk) {
                    failTransport(gen, token, "timeout 20s ${mode.label}")
                }
            }
        }
        timeout = r
        main.postDelayed(r, 20_000)
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun scheduleRetry(gen: Int) {
        cancelRetry()
        val r = Runnable {
            pjsip.post {
                if (gen == generation && wantRegister && !registeredOk) beginChain()
            }
        }
        retry = r
        main.postDelayed(r, 30_000)
    }

    private fun cancelRetry() {
        retry?.let { main.removeCallbacks(it) }
        retry = null
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
            // Not a codec-manager entry. pjmedia_endpt_create_audio_sdp adds
            // telephone-event/8000 at payload type 120 when an 8 kHz codec is offered.
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

    private fun destroyStack() {
        closing = true
        val recorded = callRecording.stop()
        stopRtp()
        val dropped = _call.value
        if (callObj != null && dropped != null && dropped.phase != CallPhase.ENDED) {
            SpikeLog.append("hangup party=local-stack-teardown")
        }
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
                limit = ConfigStore.config.value?.historyLimit ?: 200,
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
        try { account?.shutdown() } catch (_: Exception) {}
        try { account?.delete() } catch (_: Exception) {}
        account = null
        val endpoint = ep
        ep = null
        if (endpoint != null) {
            try { endpoint.libDestroy() } catch (e: Exception) {
                SpikeLog.append("libDestroy: ${e.message}")
            }
            try { endpoint.delete() } catch (_: Exception) {}
        }
        logWriter = null
        try { AddrOverride.set(null, null) } catch (_: UnsatisfiedLinkError) {}
        abandonAudio()
        closing = false
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
        if (!wantRegister) return@Runnable
        if (callInProgress()) {
            pendingReregister = true
            SpikeLog.append("network $reason changed ($previous -> $key) deferred until the call ends")
            return@Runnable
        }
        pjsip.post {
            when {
                sig != planSig -> {
                    SpikeLog.append("re-register after $reason signature $previous -> $key")
                    beginChain()
                }
                registeredOk -> {
                    try {
                        ep?.handleIpChange(IpChangeParam())
                        account?.setRegistration(true)
                        SpikeLog.append("re-register after $reason ($key)")
                    } catch (e: Exception) {
                        SpikeLog.append("re-register failed: ${e.message}\n${trace(e)}")
                    }
                }
                !attempting -> beginChain()
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

    private fun onReg(code: Int, reason: String, expiration: Long, status: Int) {
        if (closing) return
        SpikeLog.append("REGISTER $code $reason exp=$expiration status=$status")
        if (!wantRegister) return
        val mode = currentMode ?: return
        val gen = generation
        val token = attemptToken
        when {
            code in 200..299 -> win(mode)
            code == 401 || code == 407 -> {
                if (sawAuth) failTransport(gen, token, "REGISTER $code $reason")
                else {
                    sawAuth = true
                    SpikeLog.append("auth challenge, waiting for authenticated REGISTER")
                }
            }
            code >= 300 -> failTransport(gen, token, "REGISTER $code $reason")
        }
    }

    private fun onIncoming(acc: Account, callId: Int) {
        if (!wantRegister) {
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
        if (_call.value != null && _call.value?.phase != CallPhase.ENDED) {
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
            // No-arg CallOpParam leaves audioCount at 0, and isEmpty() so pjsua
            // keeps the default audio line. 180 must not init media; 200 does.
            val ringing = CallOpParam()
            ringing.statusCode = 180
            next.answer(ringing)
            signaled = false
            uiCleared = false
            callRecording.reset()
            val from = displayNumber(runCatching { next.info.remoteUri }.getOrDefault(""))
            SpikeLog.append("INVITE incoming from $from")
            val ui = CallUi(Direction.INCOMING, from, CallPhase.RINGING_IN)
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
        if (closing) return
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
                callRecording.markConfirmed()
                callRecording.startIfReady(audioMedia, captureMedia())
            }
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                if (ui != null && DialTarget.shouldRetryViaClient(
                        ConfigStore.outboundRoute(),
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
                    ConfigStore.outboundRoute() == OutboundRoute.DIRECT && !ClientProbe.answers
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
        if (closing) return
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
        if (closing) return
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
        val lines = dump.lineSequence().map { it.trim() }.filter { line ->
            line.startsWith("m=") || line.startsWith("c=") ||
                line.startsWith("a=rtpmap") || line.startsWith("a=send") || line.startsWith("a=inactive")
        }.take(30).joinToString("\n")
        if (lines.isNotEmpty()) SpikeLog.append("sdp\n$lines")
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
        if (!closing && attempting && !registeredOk &&
            prm.state == pjsip_transport_state.PJSIP_TP_STATE_DISCONNECTED
        ) {
            val gen = generation
            val token = attemptToken
            val err = prm.lastError
            pjsip.post { failTransport(gen, token, "transport disconnected err=$err") }
        }
    }

    /**
     * Sends the SIP hangup first when the user ended the call, then clears
     * the local call. A remote disconnect skips a second hangup.
     */
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
                limit = ConfigStore.config.value?.historyLimit ?: 200,
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
        if (pendingReregister) {
            pjsip.post {
                if (!pendingReregister || callObj != null || !wantRegister) return@post
                pendingReregister = false
                SpikeLog.append("re-register deferred network change now that the call ended")
                beginChain()
            }
        }
    }

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
            main.post { _line.value = _line.value.copy(rtpIn = inn, rtpOut = out) }
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
        val sig = TransportPlan.signature(snap.summary, snap.hasIpv4)
        main.post {
            _line.value = _line.value.copy(network = snap.summary, signature = sig)
        }
    }

    private fun publishTransport() {
        val mode = currentMode
        val remembered = ConfigStore.winner(planSig)
        val override = ConfigStore.transportOverride()
        val label = override?.uiLabel ?: mode?.uiLabel ?: "auto"
        main.post {
            _line.value = _line.value.copy(
                transport = label,
                remembered = remembered?.uiLabel ?: "none",
                signature = planSig.ifBlank { _line.value.signature },
                username = username,
                manual = ConfigStore.manualCreds() != null,
            )
        }
    }

    private fun setReg(reg: Registration, status: String) {
        main.post {
            _registration.value = reg
            _line.value = _line.value.copy(
                registration = reg,
                status = status,
                username = username,
                manual = ConfigStore.manualCreds() != null,
            )
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

    private class SpikeAccount(private val engine: SipEngine) : Account() {
        override fun onRegState(prm: OnRegStateParam) {
            val code = prm.code
            val reason = prm.reason ?: ""
            val expiration = prm.expiration
            val status = prm.status
            engine.pjsip.post { engine.onReg(code, reason, expiration, status) }
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            engine.onIncoming(this, prm.callId)
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
