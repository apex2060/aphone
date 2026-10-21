package com.twentytwo.phone.sipspike

/**
 * Outbound target used by the Plivo Android SDK (resip `sendSdpOffer`).
 *
 * 22 Phone passes the normalized digits to `Outgoing.call`. The SDK builds
 * `sip:<digits>@phone.plivo.com;transport=tls` and sends it through the
 * outbound proxy `client.plivo.com`. The From AOR stays
 * `sip:<endpoint>@phone.plivo.com`. No X-PH headers are added.
 */
object DialTarget {
    const val DOMAIN = "phone.plivo.com"
    const val PROXY_HOST = "client.plivo.com"

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

    fun requestUri(number: String, tls: Boolean): String {
        val transport = if (tls) "tls" else "udp"
        if (number.startsWith("sip:", ignoreCase = true)) {
            return if (number.contains(";transport=", ignoreCase = true)) number
            else "$number;transport=$transport"
        }
        return "sip:$number@$DOMAIN;transport=$transport"
    }

    fun registrarUri(tls: Boolean): String = endpointUri(DOMAIN, tls)

    fun proxyUri(tls: Boolean): String = endpointUri(PROXY_HOST, tls) + ";lr"

    private fun endpointUri(host: String, tls: Boolean): String {
        val port = if (tls) 5061 else 5060
        val transport = if (tls) "tls" else "udp"
        return "sip:$host:$port;transport=$transport"
    }
}
