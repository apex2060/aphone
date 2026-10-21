package com.twentytwo.phone.sipspike

import org.junit.Assert.assertEquals
import org.junit.Test

class DialTargetTest {
    @Test
    fun tenDigitsGetCountryCodeAndTlsTransport() {
        val digits = DialTarget.normalize("(415) 555-1212", countryCode = "1")
        assertEquals("14155551212", digits)
        assertEquals(
            "sip:14155551212@phone.plivo.com;transport=tls",
            DialTarget.requestUri(digits, tls = true),
        )
    }

    @Test
    fun plusAndElevenDigitsStayWithoutAPlus() {
        assertEquals("14155551212", DialTarget.normalize("+1 (415) 555-1212", "1"))
        assertEquals("14155551212", DialTarget.normalize("14155551212", "1"))
    }

    @Test
    fun otherCountryCodesAreNotForcedToOne() {
        assertEquals("4155551212", DialTarget.normalize("4155551212", countryCode = "44"))
    }

    @Test
    fun udpRequestAndProxyUsePort5060() {
        assertEquals(
            "sip:14155551212@phone.plivo.com;transport=udp",
            DialTarget.requestUri("14155551212", tls = false),
        )
        assertEquals("sip:client.plivo.com:5060;transport=udp;lr", DialTarget.proxyUri(tls = false))
        assertEquals("sip:phone.plivo.com:5061;transport=tls", DialTarget.registrarUri(tls = true))
    }

    @Test
    fun explicitSipUriKeepsItsHostAndGainsTransport() {
        assertEquals(
            "sip:alice@example.com;transport=tls",
            DialTarget.requestUri("sip:alice@example.com", tls = true),
        )
        assertEquals(
            "sip:alice@example.com;transport=udp",
            DialTarget.requestUri("sip:alice@example.com;transport=udp", tls = true),
        )
    }
}
