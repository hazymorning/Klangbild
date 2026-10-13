package io.github.hazymorning.klangbild.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaTokens
import io.github.hazymorning.klangbild.text.TestTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URI

/**
 * The setup server on real sockets: what has arrived is answered, even when the answer ends the
 * setup; once a stop has returned, the port takes no connection and the page does not come back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SetupServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stored = Settings(haUrl = "http://192.168.1.5:8123", haToken = "secret-token", entityId = "media_player.alt")
    private lateinit var server: SetupServer

    private val handler = SetupHandler(
        settings = { stored },
        texts = { TestTexts.german },
        discover = { _, _ -> DiscoverResult.Ok(emptyList()) },
        // As on the TV: saving leaves the setup screen, which stops the server.
        save = { updated ->
            stored = updated
            server.stop()
        },
        clientId = { null },
        exchange = { _, _, _ -> HaTokens("a", "r", 1800) },
        wrongCodeDelayMs = 0L,
    )

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        scope.cancel()
    }

    private fun start(): URI {
        server = SetupServer(scope, handler, addressProvider = { InetAddress.getLoopbackAddress() })
        server.start()
        return URI(server.origin!!)
    }

    /** Sends [body] to [path] as the page does; the whole answer, or "" when the connection was cut. */
    private fun post(at: URI, path: String, body: String): String = Socket(at.host, at.port).use { s ->
        s.soTimeout = 10_000
        val bytes = body.toByteArray()
        val head = "POST $path HTTP/1.1\r\nHost: ${at.host}:${at.port}\r\nOrigin: http://${at.host}:${at.port}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
        try {
            String(s.getInputStream().readBytes())
        } catch (_: IOException) {
            ""
        }
    }

    /** Asks for [path] as a browser does; the whole answer. */
    private fun get(at: URI, path: String): String = send(at, "GET $path HTTP/1.1\r\nHost: ${at.host}:${at.port}\r\n\r\n")

    /** Sends [request] as it is; the whole answer. */
    private fun send(at: URI, request: String): String = Socket(at.host, at.port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        String(s.getInputStream().readBytes())
    }

    @Test
    fun `only the page itself is answered, at its own address and in JSON`() {
        val at = start()
        val own = "${at.host}:${at.port}"
        val body = """{"code":"${handler.code}"}"""
        fun call(host: String, origin: String?, type: String) = send(
            at,
            "POST /api/state HTTP/1.1\r\nHost: $host\r\n" + (origin?.let { "Origin: $it\r\n" } ?: "") +
                "Content-Type: $type\r\nContent-Length: ${body.length}\r\n\r\n$body",
        )
        assertThat(call(own, "http://$own", "application/json")).startsWith("HTTP/1.1 200")
        // Another name that leads to the TV (a page that rebinds its own name to the TV's address).
        assertThat(call("tv.example.com:${at.port}", "http://$own", "application/json")).startsWith("HTTP/1.1 403")
        assertThat(send(at, "GET / HTTP/1.1\r\nHost: tv.example.com:${at.port}\r\n\r\n")).startsWith("HTTP/1.1 403")
        // Another page in the phone's browser.
        assertThat(call(own, "http://example.com", "application/json")).startsWith("HTTP/1.1 403")
        // A plain form, which any page may send without asking.
        assertThat(call(own, "http://$own", "text/plain")).startsWith("HTTP/1.1 403")
        assertThat(call(own, null, "application/x-www-form-urlencoded")).startsWith("HTTP/1.1 403")
    }

    @Test
    fun `the save that ends the setup still gets its answer`() {
        val at = start()
        val answer = post(at, "/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz"}""")
        assertThat(answer).startsWith("HTTP/1.1 200")
        assertThat(answer).contains(""""ok":true""")
        assertThat(stored.entityId).isEqualTo("media_player.wz")
        // Serving has stopped: nobody else gets in.
        assertThat(server.origin).isNull()
        assertThat(runCatching { Socket(at.host, at.port).close() }.isFailure).isTrue()
    }

    @Test
    fun `a request still arriving when serving stops is cut off`() {
        val at = start()
        Socket(at.host, at.port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write("POST /api/state HTTP/1.1\r\nHost: ${at.host}:${at.port}\r\n".toByteArray())
                flush()
            }
            // The server has the connection before it stops.
            Thread.sleep(200)
            server.stop()
            val read = try {
                s.getInputStream().read()
            } catch (_: IOException) {
                -1
            }
            assertThat(read).isEqualTo(-1)
        }
    }

    @Test
    fun `once a stop has returned the port takes no connection, not even at once`() {
        start()
        // The system takes connections on a closed socket while a thread is blocked in its accept():
        // a stop that did not wait for it let some in. Many rounds, so a stop that does not wait shows.
        repeat(100) { round ->
            if (round > 0) server.start()
            val at = URI(server.origin!!)
            // Serving: a page comes back, and the server waits for the next connection.
            assertThat(get(at, "/favicon.ico")).startsWith("HTTP/1.1 204")
            server.stop()
            assertWithMessage("a connection got in after the stop in round $round")
                .that(runCatching { Socket(at.host, at.port).close() }.isFailure).isTrue()
        }
    }

    @Test
    fun `a new code after the stop does not bring the page back`() {
        start()
        server.stop()
        handler.rotateCode()
        assertThat(server.info.value).isNull()
        assertThat(server.origin).isNull()
    }

    @Test
    fun `without an address of its own nothing listens, until there is one`() {
        var address: InetAddress? = null
        server = SetupServer(scope, handler, addressProvider = { address })
        server.start()
        val info = server.info.value!!
        assertThat(info.url).isNull()
        assertThat(info.code).isEqualTo(handler.code)
        val loopback = InetAddress.getLoopbackAddress()
        for (port in SetupServer.PORTS) {
            assertWithMessage("port $port").that(runCatching { Socket(loopback, port).close() }.isFailure).isTrue()
        }
        // Asked again (the screen asks every second), it keeps its code.
        server.start()
        assertThat(server.info.value!!.code).isEqualTo(info.code)
        // The network is there: the page is served.
        address = loopback
        server.start()
        val at = URI(server.origin!!)
        assertThat(get(at, "/favicon.ico")).startsWith("HTTP/1.1 204")
    }

    @Test
    fun `discovery tells which answer Home Assistant gave`(): Unit = runBlocking {
        MockWebServer().use { ha ->
            ha.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(502).build()
            }
            ha.start()
            val t = TestTexts.english()
            val found = SetupServer.discover(OkHttpClient(), ha.url("/").toString().trimEnd('/'), HaCredentials.LongLived("t"), t)
            assertThat((found as DiscoverResult.Failed).message).isEqualTo(t.format(R.string.discover_unexpected, 502))
        }
    }
}
