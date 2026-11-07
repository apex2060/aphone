package com.apex2060.softphone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainChromeTest {
    @Test
    fun colorsMatchTheSampledArtworkField() {
        assertEquals(0xFF6CC8FD.toInt(), MainChrome.TOOLBAR)
        assertEquals(0xFFFDF8F0.toInt(), MainChrome.CREAM)
        assertEquals(0xFFFDD496.toInt(), MainChrome.GOLD)
        assertEquals(0.46f, MainChrome.CREAM_STOP)
        assertEquals(0xFF0A2A4A.toInt(), MainChrome.ON_CHROME)
        assertEquals(0xFF1A4468.toInt(), MainChrome.ON_CHROME_MUTED)
    }

    @Test
    fun lightFieldKeepsTextAboveNormalContrast() {
        val backgrounds = listOf(MainChrome.TOOLBAR, MainChrome.CREAM, MainChrome.GOLD)
        for (background in backgrounds) {
            assertTrue(
                "navy on ${hex(background)}",
                contrast(MainChrome.ON_CHROME, background) >= 4.5,
            )
            assertTrue(
                "muted on ${hex(background)}",
                contrast(MainChrome.ON_CHROME_MUTED, background) >= 4.5,
            )
        }
        val quiet = blend(MainChrome.ON_CHROME, MainChrome.TOOLBAR, MainChrome.TOOLBAR_QUIET_ALPHA)
        assertTrue(contrast(quiet, MainChrome.TOOLBAR) >= 4.5)
        assertTrue(contrast(0xFFFFFFFF.toInt(), MainChrome.TOOLBAR) < 3.0)
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private fun blend(foreground: Int, background: Int, alpha: Float): Int {
        fun channel(fg: Int, bg: Int) = Math.round(fg * alpha + bg * (1f - alpha))
        return (channel(foreground shr 16 and 0xFF, background shr 16 and 0xFF) shl 16) or
            (channel(foreground shr 8 and 0xFF, background shr 8 and 0xFF) shl 8) or
            channel(foreground and 0xFF, background and 0xFF)
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel(argb shr 16 and 0xFF)
        val g = channel(argb shr 8 and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
