package com.apex2060.softphone

enum class TransportMode(val label: String, val uiLabel: String) {
    TLS_IPV4("TLS phone.plivo.com:5061", "TLS"),
    TLS_IPV6("TLS IPv6 mobile.phone.plivo.com (SNI phone.plivo.com)", "TLS IPv6"),
    UDP("UDP phone.plivo.com:5060", "UDP"),
}

/**
 * Auto transport order. TLS to phone.plivo.com:5061 is first. A cellular
 * network with no IPv4 source (including no CLAT) skips that and starts at
 * the IPv6 pin. A transport that already succeeded for this network signature
 * is tried first. A manual override is the only attempt.
 */
object TransportPlan {
    fun signature(summary: String, hasIpv4: Boolean): String {
        val kind = summary.substringBefore(' ').lowercase()
        val base = when {
            "cellular" in kind -> "cellular"
            "wifi" in kind -> "wifi"
            "ethernet" in kind -> "ethernet"
            else -> "other"
        }
        return if (base == "cellular" && !hasIpv4) "cellular-no-ipv4" else base
    }

    fun attempts(
        signature: String,
        remembered: TransportMode?,
        override: TransportMode?,
    ): List<TransportMode> {
        if (override != null) return listOf(override)
        val chain = if (signature == "cellular-no-ipv4") {
            listOf(TransportMode.TLS_IPV6, TransportMode.UDP)
        } else {
            listOf(TransportMode.TLS_IPV4, TransportMode.TLS_IPV6, TransportMode.UDP)
        }
        val first = remembered?.takeIf { it in chain }
        return (listOfNotNull(first) + chain).distinct()
    }

    /** Type plus routable addresses. Capabilities callbacks compare this. */
    fun networkKey(signature: String, addresses: List<String>): String =
        signature + "|" + addresses.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
}
