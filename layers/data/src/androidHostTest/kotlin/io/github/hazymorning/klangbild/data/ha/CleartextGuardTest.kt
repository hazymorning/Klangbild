package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import javax.net.SocketFactory
import kotlin.test.Test

/** The guard before a connection (by the name) and on one (by the address it reached), directly and through a proxy. */
class CleartextGuardTest {
    /** One public name resolves privately, as a home's own DNS may answer; every other name publicly. */
    private val guard = CleartextGuard { host ->
        listOf(if (host == "ha.myhome.example") ip("192.168.1.5") else ip("93.184.216.34"))
    }

    /** A literal address, read without asking DNS. */
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    /** A connection that reached [address], directly or through [proxy]. */
    private fun route(address: InetSocketAddress, proxy: Proxy = Proxy.NO_PROXY) = Route(
        Address(
            uriHost = "example",
            uriPort = 80,
            dns = Dns.SYSTEM,
            socketFactory = SocketFactory.getDefault(),
            sslSocketFactory = null,
            hostnameVerifier = null,
            certificatePinner = null,
            proxyAuthenticator = Authenticator.NONE,
            proxy = null,
            protocols = listOf(Protocol.HTTP_1_1),
            connectionSpecs = listOf(ConnectionSpec.CLEARTEXT),
            proxySelector = ProxySelector.getDefault(),
        ),
        proxy,
        address,
    )

    private fun route(literal: String) = route(InetSocketAddress(ip(literal), 80))

    /** Whether the guard lets a request for [url] on, as an application interceptor (no [route]) or a network one. */
    private fun passes(url: String, route: Route? = null): Boolean {
        val request = Request.Builder().url(url).build()
        val connection = route?.let { r ->
            object : Connection {
                override fun route() = r

                override fun socket() = Socket()

                override fun handshake(): Handshake? = null

                override fun protocol() = Protocol.HTTP_1_1
            }
        }
        // A chain that offers only what the guard reads.
        val chain = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> request
                "connection" -> connection
                "proceed" -> Response.Builder().request(args[0] as Request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Interceptor.Chain
        return try {
            guard.intercept(chain)
            true
        } catch (_: LocalNetwork.CleartextRefused) {
            false
        }
    }

    @Test
    fun `before a connection, the name decides`() {
        assertThat(passes("http://homeassistant.local:8123/api/states")).isTrue()
        assertThat(passes("http://192.168.1.5:8123/api/states")).isTrue()
        assertThat(passes("http://ha.myhome.example/api/states")).isTrue()
        assertThat(passes("http://cdn.example.org/cover.jpg")).isFalse()
        assertThat(passes("https://cdn.example.org/cover.jpg")).isTrue()
    }

    @Test
    fun `on a connection, the address it reached decides`() {
        // A local name that DNS answers with a public address gets nothing.
        assertThat(passes("http://homeassistant.local/api/states", route("93.184.216.34"))).isFalse()
        assertThat(passes("http://cdn.example.org/cover.jpg", route("192.168.1.5"))).isTrue()
        assertThat(passes("http://[fe81::1]/cover.jpg", route("fe81::1"))).isTrue()
        assertThat(passes("http://[2001:db8::1]/cover.jpg", route("2001:db8::1"))).isFalse()
        assertThat(passes("https://cdn.example.org/cover.jpg", route("93.184.216.34"))).isTrue()
    }

    @Test
    fun `through a proxy, both the name and the proxy must be local`() {
        val home = InetSocketAddress(ip("192.168.1.2"), 3128)
        val outside = InetSocketAddress(ip("203.0.113.9"), 3128)
        assertThat(passes("http://homeassistant.local/api/states", route(home, Proxy(Proxy.Type.HTTP, home)))).isTrue()
        assertThat(passes("http://cdn.example.org/cover.jpg", route(home, Proxy(Proxy.Type.HTTP, home)))).isFalse()
        assertThat(passes("http://homeassistant.local/api/states", route(outside, Proxy(Proxy.Type.HTTP, outside)))).isFalse()
        // A SOCKS proxy resolves the name itself, so where it leads is unknown.
        val socks = route(InetSocketAddress.createUnresolved("homeassistant.local", 80), Proxy(Proxy.Type.SOCKS, home))
        assertThat(passes("http://homeassistant.local/api/states", socks)).isFalse()
    }
}
