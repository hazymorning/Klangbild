package io.github.hazymorning.klangbild

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.data.WebSocketLink
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidPlatformTest {
    private val platform = AndroidPlatform(ApplicationProvider.getApplicationContext())

    @Test
    fun `a picture follows a redirect, a request with a token never does`() {
        MockWebServer().use { server ->
            server.start()
            val moved = MockResponse.Builder().code(302).addHeader("Location", "/moved").build()
            server.enqueue(moved)
            server.enqueue(MockResponse.Builder().body("picture").build())
            server.enqueue(moved)
            runBlocking {
                assertThat(platform.http.get(server.url("/cover").toString()).bodyAsText()).isEqualTo("picture")
                assertThat(platform.tokenHttp.get(server.url("/api/states").toString()).status.value).isEqualTo(302)
            }
        }
    }

    @Test
    fun `a WebSocket with a token, given up, lets go of a server that doesn't answer`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val opened = CountDownLatch(1)
            val link = WebSocketLink(scope, platform.tokenHttp, "http://127.0.0.1:${server.localPort}/api/websocket", object : WebSocketLink.Listener {
                override fun onOpen(socket: WebSocketLink) = opened.countDown()
            })
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                // The handshake, and then silence, as from a server that has died.
                val request = socket.getInputStream().bufferedReader()
                val key = generateSequence { request.readLine() }.takeWhile { it.isNotEmpty() }
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()))
                socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue()
                val start = System.nanoTime()
                link.cancel()
                // The close frame comes, and then the end of the connection.
                socket.getInputStream().readBytes()
                assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000L)
            }
            scope.cancel()
        }
    }
}
