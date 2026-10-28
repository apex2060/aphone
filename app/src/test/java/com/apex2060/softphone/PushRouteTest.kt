package com.apex2060.softphone

import com.apex2060.softphone.push.PushLine
import com.apex2060.softphone.push.PushRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRouteTest {
    private val lines = listOf(
        PushLine("a", "desk-ed22", "https://us.ed22.org/_comm?action=phone.provision", "+15551110000"),
        PushLine("b", "desk-alpha", "https://a.alphabetize.us/_comm?action=phone.provision", "+15552220000"),
    )

    @Test
    fun matchesEndpointAndOriginHost() {
        assertEquals(setOf("a"), PushRoute.select(lines, "desk-ed22", "https://us.ed22.org", null))
        assertEquals(setOf("b"), PushRoute.select(lines, "DESK-ALPHA", "a.alphabetize.us", null))
        assertTrue(PushRoute.select(lines, "desk-ed22", "https://a.alphabetize.us", null).isEmpty())
    }

    @Test
    fun absentEndpointAndOriginFallsBackToAnyLine() {
        assertEquals(setOf("a", "b"), PushRoute.select(lines, null, "  ", null))
        assertEquals(setOf("b"), PushRoute.select(lines, null, null, "15552220000"))
    }
}
