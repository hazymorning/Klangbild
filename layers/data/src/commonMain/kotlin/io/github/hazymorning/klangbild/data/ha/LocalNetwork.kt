package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.decodeIpAddress
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
        h.decodeIpAddress()?.let { return isPrivate(it) }
        if (h.contains(':')) return false
        if (!h.contains('.')) return true // single-label name like "homeassistant"
        return localSuffixes.any { h.endsWith(it) }
    }

    /** Whether [address], 4 bytes for IPv4 or 16 for IPv6, is a loopback, private or link-local address. */
    fun isPrivate(address: ByteArray): Boolean {
        fun at(i: Int) = address[i].toInt() and 0xff
        return when (address.size) {
            4 -> at(0) == 10 ||
                (at(0) == 172 && at(1) in 16..31) ||
                (at(0) == 192 && at(1) == 168) ||
                (at(0) == 169 && at(1) == 254) ||
                at(0) == 127 ||
                (at(0) == 100 && at(1) in 64..127) // CGNAT, e.g. a mesh VPN
            16 -> ((0..14).all { at(it) == 0 } && at(15) == 1) || // ::1
                (at(0) and 0xfe) == 0xfc || // fc00::/7, unique local
                (at(0) == 0xfe && (at(1) and 0x80) == 0x80) // fe80::/10, link-local, and the old site-local fec0::/10
            else -> false
        }
    }
}
