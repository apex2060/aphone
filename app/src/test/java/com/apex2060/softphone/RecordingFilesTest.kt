package com.apex2060.softphone

import com.apex2060.softphone.record.RecordingFiles
import com.apex2060.softphone.record.TapSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

class RecordingFilesTest {
    @Test
    fun savedNameUsesLocalStampAndDigits() {
        assertEquals(
            "2026-10-21_1520_15551234567.wav",
            RecordingFiles.savedName(utc("2026-10-21T15:20:00Z"), "1 (555) 123-4567", ZoneOffset.UTC),
        )
        assertEquals("unknown", RecordingFiles.numberPart("   "))
        assertEquals(
            "2026-10-21_1520_unknown.wav",
            RecordingFiles.savedName(utc("2026-10-21T15:20:00Z"), "sip:", ZoneOffset.UTC),
        )
    }

    @Test
    fun uniqueNameSkipsExistingFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rec-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val name = "2026-10-21_1520_1.wav"
            File(dir, name).writeBytes(byteArrayOf(1))
            assertEquals("2026-10-21_1520_1_2.wav", RecordingFiles.unique(dir, name).name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun orphansKeepTheLivePendingFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rec-orphan-${System.nanoTime()}").apply { mkdirs() }
        try {
            val keep = File(dir, "call-1.wav").apply { writeBytes(byteArrayOf(1)) }
            val extra = File(dir, "call-2.wav").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(listOf(extra), RecordingFiles.orphans(listOf(keep, extra), keep.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun eightQuickTapsToggleOnce() {
        val taps = TapSeries()
        var now = 1_000L
        repeat(7) {
            assertFalse(taps.hit(now))
            now += 1_000L
        }
        assertTrue(taps.hit(now))
        now += 500L
        assertFalse(taps.hit(now))
    }

    @Test
    fun aSlowGapRestartsTheCount() {
        val taps = TapSeries()
        repeat(7) { assertFalse(taps.hit(it * 1_000L)) }
        assertFalse(taps.hit(7_000L + 2_001L))
        var now = 20_000L
        repeat(7) {
            assertFalse(taps.hit(now))
            now += 2_000L
        }
        assertTrue(taps.hit(now))
    }

    private fun utc(text: String): Long = java.time.Instant.parse(text).toEpochMilli()
}
