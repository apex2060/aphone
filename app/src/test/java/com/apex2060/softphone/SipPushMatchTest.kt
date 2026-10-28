package com.apex2060.softphone

import com.apex2060.softphone.push.ProvisionActions
import com.apex2060.softphone.push.SipPushMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipPushMatchTest {
    private val invite = """
        INVITE sip:100@phone.plivo.com SIP/2.0
        From: "Ada" <sip:15551234567@phone.plivo.com>;tag=1
        X-Call-UUID: ABC-123
        CSeq: 1 INVITE
    """.trimIndent()

    @Test
    fun readsEitherHeaderFormIgnoringCase() {
        assertEquals("ABC-123", SipPushMatch.uuidFromInvite(invite))
        val renamed = invite.replace("X-Call-UUID", "x-ph-call-uuid")
        assertEquals("ABC-123", SipPushMatch.uuidFromInvite(renamed))
        assertEquals("ABC-123", SipPushMatch.uuidFromInvite(invite.replace("X-Call-UUID", "X-PH-Something-Call-UUID")))
        assertTrue(SipPushMatch.isCallUuidHeader("X-Call-UUID"))
        assertTrue(SipPushMatch.isCallUuidHeader("x-ph-Call-UUID"))
    }

    @Test
    fun headerMatchIsCaseInsensitiveAndFromIsAFallback() {
        assertEquals(SipPushMatch.Match.HEADER, SipPushMatch.matches("abc-123", "1999", "ABC-123", "15551234567"))
        assertEquals(SipPushMatch.Match.FROM, SipPushMatch.matches("abc-123", "15551234567", null, "sip:15551234567@host"))
        assertEquals(SipPushMatch.Match.NONE, SipPushMatch.matches("abc-123", "15559999999", "other", "15551234567"))
        assertNull(SipPushMatch.uuidFromInvite("SIP/2.0 200 OK\r\nCSeq: 1 REGISTER\r\n"))
    }

    @Test
    fun actionReplacesOnlyTheActionQuery() {
        assertEquals(
            "https://phones.example.com/_comm?key=front&action=phone.token",
            ProvisionActions.withAction("https://phones.example.com/_comm?action=phone.provision&key=front", "phone.token"),
        )
        assertEquals(
            "https://phones.example.com/_comm?action=phone.ready",
            ProvisionActions.withAction("https://phones.example.com/_comm", "phone.ready"),
        )
    }
}
