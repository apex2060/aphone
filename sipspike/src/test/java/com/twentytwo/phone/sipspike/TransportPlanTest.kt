package com.twentytwo.phone.sipspike

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPlanTest {
    @Test
    fun wifiTriesTlsFirstThenIpv6ThenUdp() {
        val sig = TransportPlan.signature("wifi ipv4 ipv6", hasIpv4 = true)
        assertEquals("wifi", sig)
        assertEquals(
            listOf(TransportMode.TLS_IPV4, TransportMode.TLS_IPV6, TransportMode.UDP),
            TransportPlan.attempts(sig, remembered = null, override = null),
        )
    }

    @Test
    fun cellularWithoutIpv4SkipsTlsToTheARecord() {
        val sig = TransportPlan.signature("cellular ipv6", hasIpv4 = false)
        assertEquals("cellular-no-ipv4", sig)
        assertEquals(
            listOf(TransportMode.TLS_IPV6, TransportMode.UDP),
            TransportPlan.attempts(sig, remembered = null, override = null),
        )
    }

    @Test
    fun cellularClatStillTriesTlsFirst() {
        val sig = TransportPlan.signature("cellular ipv4 clat ipv6", hasIpv4 = true)
        assertEquals("cellular", sig)
        assertEquals(TransportMode.TLS_IPV4, TransportPlan.attempts(sig, null, null).first())
    }

    @Test
    fun rememberedWinnerIsFirstAndOverrideIsExclusive() {
        val sig = "wifi"
        assertEquals(
            listOf(TransportMode.UDP, TransportMode.TLS_IPV4, TransportMode.TLS_IPV6),
            TransportPlan.attempts(sig, TransportMode.UDP, override = null),
        )
        assertEquals(
            listOf(TransportMode.TLS_IPV6),
            TransportPlan.attempts(sig, TransportMode.UDP, TransportMode.TLS_IPV6),
        )
    }

    @Test
    fun networkKeyIgnoresAddressOrderAndChangesWhenAnAddressChanges() {
        val a = TransportPlan.networkKey("cellular", listOf("ipv6 2600:1011::1", "ipv4 100.90.40.193"))
        val b = TransportPlan.networkKey("cellular", listOf("ipv4 100.90.40.193", "ipv6 2600:1011::1"))
        val c = TransportPlan.networkKey("cellular", listOf("ipv4 100.90.40.194", "ipv6 2600:1011::1"))
        assertEquals(a, b)
        assertEquals("cellular|ipv4 100.90.40.193,ipv6 2600:1011::1", a)
        assert(a != c)
    }
}

class CodecPlanTest {
    @Test
    fun fullCodecIdsKeepG711AndDisableAmr() {
        assertEquals(255, CodecPlan.priority("PCMU/8000/1"))
        assertEquals(200, CodecPlan.priority("PCMA/8000/1"))
        assertEquals(128, CodecPlan.priority("telephone-event/8000/1"))
        assertEquals(0, CodecPlan.priority("AMR/8000/1"))
        assertEquals(0, CodecPlan.priority("AMR-WB/16000/1"))
    }
}
