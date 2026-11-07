package com.apex2060.softphone.ui

/**
 * Main phone-list colors sampled from the A Phone artwork's background field,
 * not from the logo mark. Left is light cyan, the middle is cream, the right is warm gold.
 * ARGB ints, same form as [com.apex2060.softphone.config.ThemeColors].
 */
internal object MainChrome {
    /** Solid toolbar, and the left stop of the main-screen gradient. Median of the left field. */
    const val TOOLBAR = 0xFF6CC8FD.toInt()

    /** Light cream between the cyan and the gold. */
    const val CREAM = 0xFFFDF8F0.toInt()

    /** Warm yellow-gold at the right edge. */
    const val GOLD = 0xFFFDD496.toInt()

    /** Where the cream stop sits on the horizontal gradient, matching the artwork's midpoint. */
    const val CREAM_STOP = 0.46f

    /** Navy for titles and toolbar labels on the light field. */
    const val ON_CHROME = 0xFF0A2A4A.toInt()

    /** Secondary copy on the gradient. */
    const val ON_CHROME_MUTED = 0xFF1A4468.toInt()

    /**
     * Quiet toolbar label on the light bar. 0.75 of [ON_CHROME] over [TOOLBAR] falls under 4.5:1;
     * 0.85 stays above it. The dark bar keeps its previous 0.75 alpha.
     */
    const val TOOLBAR_QUIET_ALPHA = 0.85f
}
