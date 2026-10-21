package com.apex2060.softphone.record

/** Counts taps whose neighbors are at most [gapMs] apart. The [need]th tap returns true and the count restarts. */
class TapSeries(private val need: Int = 8, private val gapMs: Long = 2_000L) {
    private var count = 0
    private var last = 0L

    fun hit(now: Long): Boolean {
        if (count > 0 && now - last > gapMs) count = 0
        last = now
        count++
        if (count >= need) {
            count = 0
            return true
        }
        return false
    }
}
