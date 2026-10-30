package com.apex2060.softphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineStandbyTest {
    @Test
    fun pushStandbyIsReadyAndCallable() {
        assertEquals("Ready", LineStandby.label(true, Registration.OFFLINE, pushStandby = true))
        assertEquals("Ready · Push", statusWithPush(true, Registration.OFFLINE, pushStandby = true))
        assertTrue(LineStandby.canDial(true, Registration.OFFLINE, pushStandby = true))
        assertTrue(LineStandby.canDial(true, Registration.FAILED, pushStandby = true))
        assertEquals("Connecting", LineStandby.label(true, Registration.CONNECTING, pushStandby = true))
    }

    @Test
    fun aLineWithoutPushStillBlocksWhenOffline() {
        assertEquals("Offline", LineStandby.label(true, Registration.OFFLINE, pushStandby = false))
        assertFalse(LineStandby.canDial(true, Registration.OFFLINE, pushStandby = false))
        assertTrue(LineStandby.canDial(true, Registration.ONLINE, pushStandby = false))
        assertEquals("Off", LineStandby.label(false, Registration.ONLINE, pushStandby = true))
        assertFalse(LineStandby.canDial(false, Registration.OFFLINE, pushStandby = true))
    }

    @Test
    fun droppingAParkedLineLeavesTheOtherAccountAlone() {
        val lines = listOf(
            HeldLine("line-1", parked = true, hold = false, inCall = false),
            HeldLine("line-2", parked = true, hold = true, inCall = false),
            HeldLine("line-3", parked = false, hold = false, inCall = false),
        )
        assertEquals(setOf("line-1"), LineStandby.linesToDrop(lines, waking = emptySet()))
        assertEquals(emptySet<String>(), LineStandby.linesToDrop(lines, waking = setOf("line-1")))
    }

    @Test
    fun oneFirebaseProjectTokenIsSentToEveryLineOnThatProject() {
        val lines = listOf(
            "line-1" to "ed22",
            "line-2" to "ed22",
            "line-3" to "other",
            "line-4" to null,
        )
        assertEquals(setOf("line-1", "line-2"), LineStandby.lineIdsForProject(lines, "ed22"))
        assertEquals(setOf("line-4"), LineStandby.lineIdsForProject(lines, null))
    }

    private fun statusWithPush(enabled: Boolean, registration: Registration?, pushStandby: Boolean): String {
        val label = LineStandby.label(enabled, registration, pushStandby)
        return if (LineStandby.showsPush(enabled, pushStandby) && label == "Ready") "$label · Push" else label
    }
}
