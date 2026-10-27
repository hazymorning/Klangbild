package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.decodeIpAddress
import kotlinx.io.IOException

/**
 * Cleartext (http://) is allowed only inside the local network: loopback, private and link-local
 * addresses, single-label names, and names ending in `.local`, `.lan`, `.home`, `.internal`,
 * `.home.arpa`, `.localdomain` or `.fritz.box`. A name that resolves only to private addresses
 * counts as local too. The platform's HTTP client checks every request against these rules before
 * anything is sent.
 */
object LocalNetwork {
    /** Domains no public name ends in, and `.fritz.box`, the one a widespread home router gives its network. */
    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain", ".fritz.box")

    /** A cleartext request to a host outside the local network, refused. */
    class CleartextRefused(host: String) : IOException("Cleartext HTTP to $host is only allowed in the local network")

    /** Whether [host] is local by its name or address alone, without asking DNS. */
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
