package io.github.hazymorning.klangbild.data.ha

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

/**
 * Cleartext (http://) is only allowed inside the home network. Android's network security
 * config cannot express IP ranges, so the platform allows cleartext in general
 * (res/xml/network_security_config.xml) and this policy narrows it down again in code:
 * private and link-local addresses, `.local`/`.lan`/`.home.arpa` names and single-label
 * hostnames. A hostname that resolves only to private addresses (local DNS) is local too.
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

    fun isPrivateAddress(addr: InetAddress): Boolean =
        addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
            (addr.address.size == 4 && isPrivateIpv4(addr.address.map { it.toInt() and 0xFF })) ||
            (addr.address.size == 16 && (addr.address[0].toInt() and 0xFE) == 0xFC) // fc00::/7

    private fun parseIpv4(h: String): List<Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.all { it in 0..255 }) nums else null
    }

    private fun isPrivateIpv4(p: List<Int>): Boolean =
        p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254) ||
            p[0] == 127 ||
            (p[0] == 100 && p[1] in 64..127) // CGNAT, e.g. a mesh VPN

    private fun isPrivateIpv6(h: String): Boolean =
        h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")

    /**
     * OkHttp interceptor that refuses cleartext requests outside the home network. As an
     * application interceptor it checks the host's name before anything is sent; as a network
     * interceptor (every redirect hop, the WebSocket's handshake too) it checks the address the
     * connection actually reached, so a local looking name that leads elsewhere gets nothing.
     * [resolver] is injectable for tests.
     */
    class CleartextGuard(
        private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!url.isHttps) {
                val route = chain.connection()?.route()
                val allowed = when {
                    route == null -> isLocalHost(url.host)
                    // Through a proxy the address reached is the proxy's: the name must be local, too.
                    route.proxy.type() != Proxy.Type.DIRECT -> isLocalHost(url.host) && isPrivateAddress(route.socketAddress.address)
                    else -> isPrivateAddress(route.socketAddress.address)
                }
                if (!allowed) throw CleartextRefused(url.host)
            }
            return chain.proceed(chain.request())
        }

        fun isLocalHost(host: String): Boolean {
            if (looksLocal(host)) return true
            return try {
                val addrs = resolver(host)
                addrs.isNotEmpty() && addrs.all { isPrivateAddress(it) }
            } catch (_: Exception) {
                false
            }
        }
    }
}
