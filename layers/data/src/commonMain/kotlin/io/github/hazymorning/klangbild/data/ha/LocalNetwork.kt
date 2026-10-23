package io.github.hazymorning.klangbild.data.ha

import kotlinx.io.IOException

/**
 * Cleartext (http://) is only allowed inside the home network: private and link-local
 * addresses, `.local`/`.lan`/`.home.arpa` names and single-label hostnames. A hostname that
 * resolves only to private addresses (local DNS) is local too. The platform's HTTP client
 * checks every request against these rules before anything is sent.
 */
object LocalNetwork {
    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain", ".fritz.box", ".box")

    /** A cleartext request to a host outside the home network, refused. */
    class CleartextRefused(host: String) : IOException("Cleartext HTTP to $host is only allowed in the local network")

    /** Purely syntactic check (no DNS). */
    fun looksLocal(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        parseIpv4(h)?.let { return isPrivateIpv4(it) }
        if (h.contains(':')) return isPrivateIpv6(h)
        if (!h.contains('.')) return true // single-label name like "homeassistant"
        return localSuffixes.any { h.endsWith(it) }
    }

    private fun parseIpv4(h: String): List<Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.all { it in 0..255 }) nums else null
    }

    /** Whether the IPv4 address [p] (four numbers) is in the home network. */
    internal fun isPrivateIpv4(p: List<Int>): Boolean =
        p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254) ||
            p[0] == 127 ||
            (p[0] == 100 && p[1] in 64..127) // CGNAT, e.g. a mesh VPN

    private fun isPrivateIpv6(h: String): Boolean =
        h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
}
