package com.twentytwo.phone.sipspike

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallOpParam
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

enum class TransportMode(val label: String) {
    TLS_IPV4("TLS phone.plivo.com:5061"),
    TLS_IPV6("TLS IPv6 mobile.phone.plivo.com (SNI phone.plivo.com)"),
    UDP("UDP phone.plivo.com:5060"),
}

data class SpikeState(
    val status: String = "Idle",
    val registered: Boolean = false,
    val callPhase: String = "idle",
    val remote: String = "",
    val network: String = "",
    val busy: Boolean = false,
    val nativeError: String? = null,
)

class SipEngine(private val app: Application) {
    val lines = mutableStateListOf<String>()
    private val _state = MutableStateFlow(SpikeState())
    val state: StateFlow<SpikeState> = _state.asStateFlow()

    private val main = Handler(app.mainLooper)
    private val thread = HandlerThread("sipspike-pjsip")
    private val pjsip: Handler
    private val prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)

    @Volatile private var secret: String? = null
    @Volatile private var wantRegister = false
    private var mode = TransportMode.TLS_IPV4
    private var pinnedV6: String? = null
    private var ep: SpikeEndpoint? = null
    private var account: SpikeAccount? = null
    @Volatile private var call: SpikeCall? = null
    private var incomingRing = false
    private var logWriter: RedactingLog? = null
    private var focus: AudioFocusRequest? = null
    private var pendingNet = "change"

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
        val nativeError = loadNative()
        if (nativeError != null) {
            _state.value = SpikeState(status = "Native library failed to load", nativeError = nativeError)
            append("native load failed: $nativeError")
        }
    }

    fun savedUsername(): String = prefs.getString(KEY_USER, "") ?: ""
    fun savedPassword(): String = prefs.getString(KEY_PASS, "") ?: ""
    fun savedMode(): TransportMode {
        val name = prefs.getString(KEY_MODE, TransportMode.TLS_IPV4.name)
        return TransportMode.entries.firstOrNull { it.name == name } ?: TransportMode.TLS_IPV4
    }

    fun start() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        try {
            cm.registerDefaultNetworkCallback(netCallback)
        } catch (e: RuntimeException) {
            append("network callback: ${e.message}")
        }
        publishNet(NetInfo.snapshot(app))
    }

    fun logText(): String = LogSanitizer.redact(lines.joinToString("\n"), secret)

    fun clearLog() {
        main.post { lines.clear() }
    }

    fun register(username: String, password: String, selected: TransportMode) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            setStatus("Username and password are required")
            return
        }
        if (user.any { it.isWhitespace() || it == '@' }) {
            setStatus("Username must not contain spaces or @")
            return
        }
        if (_state.value.nativeError != null) {
            setStatus("Native library failed to load")
            return
        }
        prefs.edit()
            .putString(KEY_USER, user)
            .putString(KEY_PASS, password)
            .putString(KEY_MODE, selected.name)
            .apply()
        secret = password
        wantRegister = true
        mode = selected
        setBusy(true, "Registering ${selected.label}")
        append("register ${selected.label} aor=sip:$user@phone.plivo.com")
        pjsip.post { startStack(user, password, selected) }
    }

    fun unregister() {
        wantRegister = false
        append("unregister")
        pjsip.post {
            destroyStack()
            main.post {
                _state.value = _state.value.copy(
                    status = "Unregistered",
                    registered = false,
                    busy = false,
                    callPhase = "idle",
                    remote = "",
                )
            }
        }
    }

    fun call(raw: String) {
        val target = raw.trim()
        if (target.isEmpty()) {
            setStatus("Enter a number")
            return
        }
        val uri = if (target.startsWith("sip:", ignoreCase = true)) target
        else "sip:$target@phone.plivo.com"
        append("INVITE $uri")
        pjsip.post {
            val acc = account
            if (acc == null) {
                setStatus("Not registered")
                return@post
            }
            try {
                abandonAudio()
                incomingRing = false
                val next = SpikeCall(acc, this)
                val prm = CallOpParam(true)
                next.makeCall(uri, prm)
                call = next
                setCall("out", uri, "Calling $uri")
                engageAudio(speaker = true)
            } catch (e: Exception) {
                append("INVITE failed: ${e.message}")
                setStatus("Call failed: ${e.message}")
            }
        }
    }

    fun answer() = signal(200, "answer")
    fun decline() = signal(486, "decline")
    fun hangup() = signal(0, "hangup")

    fun dtmf(digit: String) {
        pjsip.post {
            try {
                call?.dialDtmf(digit)
                append("DTMF $digit")
            } catch (e: Exception) {
                append("DTMF failed: ${e.message}")
            }
        }
    }

    fun setSpeaker(on: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = on
        append("speaker=$on")
    }

    private fun signal(code: Int, name: String) {
        append(name)
        pjsip.post {
            val current = call
            if (current == null) {
                setStatus("No call")
                return@post
            }
            try {
                val prm = CallOpParam()
                if (name == "answer") {
                    prm.statusCode = 200
                    current.answer(prm)
                } else if (name == "decline") {
                    prm.statusCode = 486
                    current.hangup(prm)
                } else {
                    current.hangup(CallOpParam())
                }
            } catch (e: Exception) {
                append("$name failed: ${e.message}")
                setStatus("$name failed: ${e.message}")
            }
        }
    }

    private fun startStack(user: String, password: String, selected: TransportMode) {
        try {
            destroyStack()
            val net = NetInfo.snapshot(app)
            publishNet(net)
            append(net.detail)
            val ca = caFile()
            if (selected == TransportMode.TLS_IPV6) {
                val v6 = resolveAaaa() ?: return
                if (!AddrOverride.set("phone.plivo.com", v6)) {
                    fail("Could not pin phone.plivo.com to $v6")
                    return
                }
                pinnedV6 = v6
                append("pinned phone.plivo.com -> $v6 (AAAA of mobile.phone.plivo.com); TLS name phone.plivo.com")
            } else {
                AddrOverride.set(null, null)
                pinnedV6 = null
                append("DNS override cleared")
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
            cfg.uaConfig.userAgent = "22SIPTest/0.1"
            cfg.medConfig.clockRate = 16000
            cfg.medConfig.sndClockRate = 16000
            endpoint.libInit(cfg)

            val tp = TransportConfig()
            tp.port = 0
            if (selected != TransportMode.UDP) {
                tp.tlsConfig.verifyServer = true
                tp.tlsConfig.caListFile = ca.absolutePath
                tp.tlsConfig.msecTimeout = 15_000
            }
            val type = when (selected) {
                TransportMode.TLS_IPV4 -> pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
                TransportMode.TLS_IPV6 -> pjsip_transport_type_e.PJSIP_TRANSPORT_TLS6
                TransportMode.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
            }
            endpoint.transportCreate(type, tp)
            endpoint.libStart()
            tuneCodecs(endpoint)
            logAudio(endpoint)

            val mediaIpv6 = if (selected == TransportMode.TLS_IPV6 && !net.hasIpv4) {
                pjsua_ipv6_use.PJSUA_IPV6_ENABLED_USE_IPV6_ONLY
            } else {
                pjsua_ipv6_use.PJSUA_IPV6_ENABLED_PREFER_IPV4
            }
            val sipIpv6 = when (selected) {
                TransportMode.TLS_IPV6 -> pjsua_ipv6_use.PJSUA_IPV6_ENABLED_USE_IPV6_ONLY
                else -> pjsua_ipv6_use.PJSUA_IPV6_DISABLED
            }
            append("sip ipv6=$sipIpv6 media ipv6=$mediaIpv6 srtp=disabled ice=off")

            val accCfg = AccountConfig()
            accCfg.idUri = "sip:$user@phone.plivo.com"
            accCfg.regConfig.registrarUri = when (selected) {
                TransportMode.TLS_IPV4, TransportMode.TLS_IPV6 ->
                    "sip:phone.plivo.com:5061;transport=tls"
                TransportMode.UDP -> "sip:phone.plivo.com:5060;transport=udp"
            }
            accCfg.regConfig.timeoutSec = 120
            accCfg.regConfig.retryIntervalSec = 30
            accCfg.sipConfig.authCreds.add(AuthCredInfo("digest", "*", user, 0, password))
            accCfg.sipConfig.ipv6Use = sipIpv6
            accCfg.natConfig.iceEnabled = false
            accCfg.natConfig.contactRewriteUse = 1
            accCfg.natConfig.viaRewriteUse = 1
            accCfg.natConfig.sdpNatRewriteUse = 1
            accCfg.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
            accCfg.mediaConfig.srtpSecureSignaling = 0
            accCfg.mediaConfig.ipv6Use = mediaIpv6
            val acc = SpikeAccount(this)
            acc.create(accCfg, true)
            account = acc
            append("account created registrar=${accCfg.regConfig.registrarUri}")
            setBusy(false, "REGISTER sent (${selected.label})")
        } catch (e: Exception) {
            append("register failed: ${e.message}")
            fail(e.message ?: "register failed")
            destroyStack()
        }
    }

    private fun resolveAaaa(): String? {
        val all = try {
            InetAddress.getAllByName("mobile.phone.plivo.com").toList()
        } catch (e: Exception) {
            fail("AAAA lookup failed: ${e.message}")
            return null
        }
        append("mobile.phone.plivo.com resolved:")
        for (addr in all) {
            val kind = if (addr is Inet6Address) "AAAA" else "A"
            append("  $kind ${addr.hostAddress}")
        }
        val v6 = all.filterIsInstance<Inet6Address>()
            .firstOrNull { !it.isLinkLocalAddress }
        if (v6 == null) {
            fail("mobile.phone.plivo.com has no global AAAA on this network")
            return null
        }
        return v6.hostAddress?.substringBefore('%')
    }

    private fun tuneCodecs(endpoint: Endpoint) {
        val codecs = endpoint.codecEnum2()
        var sawEvent = false
        for (i in 0 until codecs.size) {
            val info = codecs.get(i)
            val id = info.codecId
            val priority = when {
                id.equals("PCMU/8000", true) -> 255
                id.equals("PCMA/8000", true) -> 200
                id.startsWith("telephone-event/8000", true) -> {
                    sawEvent = true
                    128
                }
                else -> 0
            }.toShort()
            endpoint.codecSetPriority(id, priority)
            if (priority > 0) append("codec $id priority=$priority")
        }
        if (!sawEvent) append("warning: telephone-event/8000 was not in the codec list")
    }

    private fun logAudio(endpoint: Endpoint) {
        try {
            val mgr = endpoint.audDevManager()
            val info = mgr.getDevInfo(mgr.captureDev)
            append("audio capture: ${info.name}")
        } catch (e: Exception) {
            append("audio device: ${e.message}")
        }
    }

    private fun destroyStack() {
        try { call?.hangup(CallOpParam()) } catch (_: Exception) {}
        try { call?.delete() } catch (_: Exception) {}
        call = null
        try { account?.shutdown() } catch (_: Exception) {}
        try { account?.delete() } catch (_: Exception) {}
        account = null
        val endpoint = ep
        ep = null
        if (endpoint != null) {
            try { endpoint.libDestroy() } catch (e: Exception) {
                append("libDestroy: ${e.message}")
            }
            try { endpoint.delete() } catch (_: Exception) {}
        }
        logWriter = null
        try { AddrOverride.set(null, null) } catch (_: UnsatisfiedLinkError) {}
        pinnedV6 = null
        incomingRing = false
        abandonAudio()
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
        append("network $reason\n${snap.detail}")
        if (!wantRegister || ep == null) return@Runnable
        pjsip.post { refreshAfterNetwork(reason, snap) }
    }

    private fun refreshAfterNetwork(reason: String, snap: NetSnapshot) {
        if (!wantRegister || ep == null) return
        try {
            if (mode == TransportMode.TLS_IPV6) {
                val v6 = resolveAaaa()
                if (v6 != null && v6 != pinnedV6) {
                    if (!AddrOverride.set("phone.plivo.com", v6)) {
                        append("pin failed for $v6")
                    } else {
                        pinnedV6 = v6
                        append("re-pinned phone.plivo.com -> $v6")
                    }
                }
            }
            ep?.handleIpChange(IpChangeParam())
            account?.setRegistration(true)
            append("re-register after $reason (${snap.summary})")
        } catch (e: Exception) {
            append("re-register failed: ${e.message}")
        }
    }

    private fun onReg(prm: OnRegStateParam) {
        val line = "REGISTER ${prm.code} ${prm.reason} exp=${prm.expiration} status=${prm.status}"
        append(line)
        val ok = prm.code in 200..299
        main.post {
            _state.value = _state.value.copy(
                status = if (ok) "Registered ${prm.code} ${prm.reason} (expires ${prm.expiration})"
                else "REGISTER ${prm.code} ${prm.reason}",
                registered = ok,
                busy = false,
            )
        }
    }

    private fun onIncoming(acc: Account, callId: Int) {
        try {
            // Set before the Call wrapper exists. Sending 180 moves the invite
            // to EARLY, and that callback must still count as an incoming ring.
            incomingRing = true
            val next = SpikeCall(acc, callId, this)
            val ringing = CallOpParam()
            ringing.statusCode = 180
            next.answer(ringing)
            call = next
            val from = try {
                next.info.remoteUri
            } catch (_: Exception) {
                "unknown"
            }
            append("incoming $from")
            setCall("in", from, "Incoming $from")
        } catch (e: Exception) {
            append("incoming failed: ${e.message}")
        }
    }

    private fun onCallState(current: SpikeCall) {
        val info = try { current.info } catch (e: Exception) {
            append("call state: ${e.message}")
            return
        }
        append("call ${info.stateText} ${info.lastStatusCode} ${info.lastReason} ${info.remoteUri}")
        when (info.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                incomingRing = false
                setCall("active", info.remoteUri, "In call ${info.remoteUri}")
                engageAudio(speaker = true)
            }
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                incomingRing = false
                setCall("idle", "", "Call ended ${info.lastStatusCode} ${info.lastReason}")
                abandonAudio()
                if (call === current) call = null
                pjsip.post {
                    try { current.delete() } catch (_: Exception) {}
                }
            }
            pjsip_inv_state.PJSIP_INV_STATE_CALLING ->
                setCall("out", info.remoteUri, info.stateText + " " + info.remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_INCOMING ->
                setCall("in", info.remoteUri, "Incoming ${info.remoteUri}")
            pjsip_inv_state.PJSIP_INV_STATE_EARLY,
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> {
                val phase = if (incomingRing) "in" else "out"
                setCall(phase, info.remoteUri, info.stateText + " " + info.remoteUri)
            }
        }
    }

    private fun onCallMedia(current: SpikeCall) {
        val info = try { current.info } catch (_: Exception) { return }
        val media = info.media
        val endpoint = ep ?: return
        for (i in 0 until media.size) {
            val item = media.get(i)
            val active = item.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE ||
                item.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD
            if (item.type == pjmedia_type.PJMEDIA_TYPE_AUDIO && active) {
                try {
                    val am: AudioMedia = current.getAudioMedia(i)
                    val mgr = endpoint.audDevManager()
                    mgr.captureDevMedia.startTransmit(am)
                    am.startTransmit(mgr.playbackDevMedia)
                    append("audio connected")
                    engageAudio(speaker = audio.isSpeakerphoneOn)
                } catch (e: Exception) {
                    append("audio connect failed: ${e.message}")
                }
            }
        }
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
            append(
                "transport $state type=${prm.type} err=${prm.lastError} " +
                    "local=${tls.localAddr} remote=${tls.remoteAddr} " +
                    "cipher=${tls.cipherName} verify=${tls.verifyStatus} $msgs"
            )
        } else {
            append("transport $state type=${prm.type} err=${prm.lastError}")
        }
    }

    fun redact(message: String): String = LogSanitizer.redact(message, secret)

    private fun engageAudio(speaker: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speaker
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
        audio.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= 26) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }
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

    private fun loadNative(): String? {
        return try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("pjsua2")
            null
        } catch (t: UnsatisfiedLinkError) {
            t.message ?: t.toString()
        }
    }

    private fun append(message: String) {
        val clean = LogSanitizer.redact(message, secret).trimEnd()
        if (clean.isEmpty()) return
        Log.i(TAG, clean)
        main.post {
            for (line in clean.lineSequence()) {
                if (line.isBlank()) continue
                lines.add(line)
            }
            val extra = lines.size - LOG_CAP
            if (extra > 0) lines.removeRange(0, extra)
        }
    }

    private fun publishNet(snap: NetSnapshot) {
        main.post { _state.value = _state.value.copy(network = snap.summary) }
    }

    private fun setStatus(text: String) {
        main.post { _state.value = _state.value.copy(status = text, busy = false) }
    }

    private fun setBusy(busy: Boolean, text: String) {
        main.post { _state.value = _state.value.copy(status = text, busy = busy) }
    }

    private fun fail(text: String) {
        append(text)
        setStatus(text)
    }

    private fun setCall(phase: String, remote: String, text: String) {
        main.post {
            _state.value = _state.value.copy(callPhase = phase, remote = remote, status = text, busy = false)
        }
    }

    private inner class RedactingLog : LogWriter() {
        override fun write(entry: LogEntry) {
            val msg = entry.msg ?: return
            append(msg)
        }
    }

    private class SpikeEndpoint(private val engine: SipEngine) : Endpoint() {
        override fun onTransportState(prm: OnTransportStateParam) {
            engine.onTransport(prm)
        }
    }

    private class SpikeAccount(private val engine: SipEngine) : Account() {
        override fun onRegState(prm: OnRegStateParam) {
            engine.onReg(prm)
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            engine.onIncoming(this, prm.callId)
        }
    }

    private class SpikeCall : Call {
        private val engine: SipEngine

        constructor(acc: Account, engine: SipEngine) : super(acc) {
            this.engine = engine
        }

        constructor(acc: Account, callId: Int, engine: SipEngine) : super(acc, callId) {
            this.engine = engine
        }

        override fun onCallState(prm: OnCallStateParam) {
            engine.onCallState(this)
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            engine.onCallMedia(this)
        }
    }

    companion object {
        private const val TAG = "SipSpike"
        private const val PREFS = "sipspike"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_MODE = "transport"
        private const val LOG_CAP = 1500
    }
}
