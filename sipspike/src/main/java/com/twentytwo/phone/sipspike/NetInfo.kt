package com.twentytwo.phone.sipspike

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class NetSnapshot(
    val summary: String,
    val detail: String,
    val hasIpv4: Boolean,
)

object NetInfo {
    fun snapshot(context: Context): NetSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        if (network == null) {
            return NetSnapshot("none", "network: none (no active network)", false)
        }
        val caps = cm.getNetworkCapabilities(network)
        val link = cm.getLinkProperties(network)
        val kind = transports(caps)
        val lines = mutableListOf<String>()
        lines += "network: $kind"
        if (caps != null) {
            lines += "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}" +
                " validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}" +
                " not-vpn=${!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}"
        }
        var hasIpv4 = false
        var hasIpv6 = false
        var hasClat = false
        val addrs = link?.linkAddresses.orEmpty()
        if (addrs.isEmpty()) {
            lines += "addresses: (none)"
        }
        for (la in addrs) {
            val addr = la.address
            val label = label(addr)
            if (label == "ipv4" || label == "clat") hasIpv4 = true
            if (label == "ipv6") hasIpv6 = true
            if (label == "clat") hasClat = true
            lines += "addr $label ${addr.hostAddress} prefix=${la.prefixLength}"
        }
        val dns = link?.dnsServers.orEmpty().joinToString { it.hostAddress ?: "?" }
        lines += "dns: ${dns.ifBlank { "(none)" }}"
        val summary = buildString {
            append(kind)
            append(' ')
            if (hasIpv4) append("ipv4 ")
            if (hasClat) append("clat ")
            if (hasIpv6) append("ipv6 ")
            if (!hasIpv4 && !hasIpv6) append("no-addr ")
        }.trim()
        return NetSnapshot(summary, lines.joinToString("\n"), hasIpv4)
    }

    private fun transports(caps: NetworkCapabilities?): String {
        if (caps == null) return "unknown"
        val names = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names += "wifi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names += "cellular"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names += "ethernet"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names += "vpn"
        if (Build.VERSION.SDK_INT >= 27 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
            names += "wifi-aware"
        }
        return names.joinToString("+").ifBlank { "other" }
    }

    private fun label(addr: InetAddress): String {
        if (addr.isLoopbackAddress) return "loopback"
        if (addr is Inet4Address) {
            val b = addr.address
            val last = b[3].toInt() and 0xff
            if (b[0] == 192.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && last <= 7) {
                return "clat"
            }
            return "ipv4"
        }
        if (addr is Inet6Address) {
            if (addr.isLinkLocalAddress) return "ipv6-link"
            return "ipv6"
        }
        return "other"
    }
}
