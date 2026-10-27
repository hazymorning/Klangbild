package io.github.hazymorning.klangbild.data.ha

import okhttp3.Interceptor
import okhttp3.Response
import java.net.InetAddress
import java.net.Proxy

/**
 * OkHttp interceptor that refuses cleartext requests outside the home network ([LocalNetwork]).
 * Android's network security config cannot express IP ranges, so the platform allows cleartext
 * in general (res/xml/network_security_config.xml) and this narrows it down again. As an
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
                route.proxy.type() != Proxy.Type.DIRECT -> isLocalHost(url.host) && isPrivate(route.socketAddress.address)
                else -> isPrivate(route.socketAddress.address)
            }
            if (!allowed) throw LocalNetwork.CleartextRefused(url.host)
        }
        return chain.proceed(chain.request())
    }

    fun isLocalHost(host: String): Boolean {
        if (LocalNetwork.looksLocal(host)) return true
        return try {
            val addrs = resolver(host)
            addrs.isNotEmpty() && addrs.all { isPrivate(it) }
        } catch (_: Exception) {
            false
        }
    }

    /** Whether [addr] is private. A SOCKS proxy resolves the name itself, so its route has no address, which isn't. */
    private fun isPrivate(addr: InetAddress?): Boolean = addr != null && LocalNetwork.isPrivate(addr.address)
}
