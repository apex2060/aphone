package com.twentytwo.phone.sipspike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One OPTIONS over TLS to client.plivo.com:5061. Any SIP response means the
 * host answers; a timeout or a handshake failure means an outbound Route
 * there would not be sent.
 */
object ClientProbe {
    private val _text = MutableStateFlow("not run")
    val text: StateFlow<String> = _text.asStateFlow()

    @Volatile var answers: Boolean = false
        private set

    @Volatile private var started = false

    fun startOnce() {
        if (started) return
        started = true
        _text.value = "running"
        Thread({
            val summary = runCatching { probe() }.getOrElse { e ->
                answers = false
                "failed ${e.javaClass.simpleName}: ${e.message}"
            }
            _text.value = summary
            SpikeLog.append("client.plivo.com:5061 probe $summary")
        }, "sipspike-probe").apply { isDaemon = true }.start()
    }

    private fun probe(): String {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tcp = Socket()
        tcp.connect(InetSocketAddress(DialTarget.PROXY_HOST, 5061), 8_000)
        val socket = factory.createSocket(tcp, DialTarget.PROXY_HOST, 5061, true) as SSLSocket
        socket.use { tls ->
            val params = tls.sslParameters
            params.serverNames = listOf(SNIHostName(DialTarget.PROXY_HOST))
            tls.sslParameters = params
            tls.soTimeout = 5_000
            tls.startHandshake()
            val session = tls.session
            val peer = session.peerHost ?: DialTarget.PROXY_HOST
            val msg = buildString {
                append("OPTIONS sip:${DialTarget.PROXY_HOST}:5061;transport=tls SIP/2.0\r\n")
                append("Via: SIP/2.0/TLS 192.0.2.1:5061;branch=z9hG4bK-22probe;rport\r\n")
                append("From: <sip:probe@${DialTarget.DOMAIN}>;tag=22probe\r\n")
                append("To: <sip:${DialTarget.PROXY_HOST}>\r\n")
                append("Call-ID: 22probe@sipspike\r\n")
                append("CSeq: 1 OPTIONS\r\n")
                append("Max-Forwards: 10\r\n")
                append("Content-Length: 0\r\n\r\n")
            }
            tls.outputStream.write(msg.toByteArray())
            tls.outputStream.flush()
            val body = tls.inputStream.bufferedReader().use { reader ->
                val first = reader.readLine().orEmpty()
                first
            }
            answers = body.startsWith("SIP/2.0")
            val cipher = session.cipherSuite ?: ""
            return "tls=$peer ${session.protocol} $cipher sip=${body.ifBlank { "no status line" }} answers=$answers"
        }
    }
}
