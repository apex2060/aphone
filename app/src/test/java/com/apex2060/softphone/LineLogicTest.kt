package com.apex2060.softphone

import com.apex2060.softphone.config.LineLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class LineLogicTest {
    @Test
    fun sameSipUsernameMatchesTheExistingLine() {
        assertEquals(0, LineLogic.matchingIndex(listOf(" 1001 "), "1001"))
        assertEquals(1, LineLogic.matchingIndex(listOf("1001", "1002"), "1002"))
        assertEquals(-1, LineLogic.matchingIndex(listOf("1001"), "1002"))
    }

    @Test
    fun legacyTransportWinsKeepOnlyPrefixedModeNames() {
        val wins = LineLogic.legacyWins(
            mapOf(
                "transport_win_wifi-v4" to "TLS_IPV4",
                "transport_win_cell" to "",
                "transport_win_" to "UDP",
                "config_json" to "{}",
                "transport_win_ignored" to 1,
            ),
        )
        assertEquals(mapOf("wifi-v4" to "TLS_IPV4"), wins)
    }

    @Test
    fun lineCapIsEight() {
        assertEquals(8, LineLogic.MAX_LINES)
    }
}
