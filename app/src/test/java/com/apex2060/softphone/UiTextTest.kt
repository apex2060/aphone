package com.apex2060.softphone

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test
    fun hostnamesAreScrubbedFromUiCopy() {
        assertEquals(
            "registrar=sip:server:5061;transport=tls",
            UiText.scrub("registrar=sip:phone.plivo.com:5061;transport=tls"),
        )
        assertEquals(
            "Route <sip:server:5061;transport=tls;lr>",
            UiText.scrub("Route <sip:client.plivo.com:5061;transport=tls;lr>"),
        )
        assertEquals("Wi-Fi", UiText.networkIndicator("wifi ipv4 ipv6"))
        assertEquals("Cellular", UiText.networkIndicator("cellular ipv6"))
        assertEquals("Wi-Fi · Cellular", UiText.networkIndicator("wifi+cellular ipv4"))
        assertEquals("", UiText.networkIndicator("none"))
    }
}
