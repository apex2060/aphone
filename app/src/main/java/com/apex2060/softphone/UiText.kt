package com.apex2060.softphone

/** Strings shown in the header and Details. The debug log file is left intact. */
object UiText {
    private val host = Regex("(?i)(?:[a-z0-9-]+\\.)*plivo\\.com")

    fun scrub(text: String): String = host.replace(text, "server")

    /** Wi-Fi / cellular only. Transport, IP version, and host stay out of the header. */
    fun networkIndicator(summary: String): String {
        val kind = summary.substringBefore(' ').lowercase()
        if (kind.isBlank() || kind == "none") return ""
        val wifi = "wifi" in kind
        val cell = "cellular" in kind
        return when {
            wifi && cell -> "Wi-Fi · Cellular"
            wifi -> "Wi-Fi"
            cell -> "Cellular"
            "ethernet" in kind -> "Ethernet"
            else -> ""
        }
    }
}
