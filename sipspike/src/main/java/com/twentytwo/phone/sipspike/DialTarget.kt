package com.twentytwo.phone.sipspike

/**
 * Registration stays on phone.plivo.com, the same registrar 0.3 used.
 * client.plivo.com is not an account proxy: a Route to it is added only on
 * an outbound INVITE, and only when that path is selected or a direct
 * INVITE was rejected.
 */
object DialTarget {
    const val DOMAIN = "phone.plivo.com"
    const val PROXY_HOST = "client.plivo.com"

    /** TLS SIP on the browser-SDK host. UDP 5060 did not answer REGISTER. */
    fun clientRoute(): String = "sip:$PROXY_HOST:5061;transport=tls;lr"

    /** Same digit rules as 22 Phone. A leading '+' is dropped. A 10-digit number gets country code 1. */
    fun normalize(raw: String, countryCode: String?): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("sip:", ignoreCase = true)) return trimmed
        val digits = trimmed.filter { it.isDigit() || it == '*' || it == '#' }
        return when {
            trimmed.startsWith("+") -> digits
            countryCode == "1" && digits.length == 10 -> "1$digits"
            else -> digits
        }
    }

    /**
     * Transport on the request URI keeps the INVITE on the registered
     * connection. Without it, pjsua sends a URI with no transport over UDP.
     */
    fun requestUri(number: String, tls: Boolean): String {
        val transport = if (tls) "tls" else "udp"
        if (number.startsWith("sip:", ignoreCase = true)) {
            return if (number.contains(";transport=", ignoreCase = true)) number
            else "$number;transport=$transport"
        }
        return "sip:$number@$DOMAIN;transport=$transport"
    }

    fun registrarUri(tls: Boolean): String {
        val port = if (tls) 5061 else 5060
        val transport = if (tls) "tls" else "udp"
        return "sip:$DOMAIN:$port;transport=$transport"
    }

    /** One follow-up via client.plivo.com after the direct INVITE was rejected before ringing. */
    fun shouldRetryViaClient(
        route: OutboundRoute,
        code: Int,
        sawEarly: Boolean,
        alreadyRetried: Boolean,
        probeAnswers: Boolean,
    ): Boolean {
        if (route != OutboundRoute.DIRECT || alreadyRetried || sawEarly || !probeAnswers) return false
        return code == 403 || code == 404 || code == 480 || code == 484 ||
            code == 488 || code == 500 || code == 502 || code == 503
    }
}

enum class OutboundRoute(val label: String) {
    DIRECT("phone.plivo.com direct"),
    VIA_CLIENT("via client.plivo.com"),
}
