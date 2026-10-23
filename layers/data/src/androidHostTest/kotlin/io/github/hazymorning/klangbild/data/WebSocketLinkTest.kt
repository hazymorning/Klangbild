package io.github.hazymorning.klangbild.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The WebSocket under every connection, and how it ends with the scope it was opened in. */
class WebSocketLinkTest {
    private val server = MockWebServer()
    private val http = httpClient(OkHttp.create())

    /** What the server heard: the messages, then the close. */
    private val heard = CopyOnWriteArrayList<String>()
    private val closed = CountDownLatch(1)

    @BeforeTest
    fun setUp() {
        val socket = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                heard += text
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                heard += "close $code"
                webSocket.close(code, null)
                closed.countDown()
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(socket).build())
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `when its scope ends, what was queued still goes out before a normal close`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = open(scope)
        assertThat(link.send("goodbye")).isTrue()
        scope.cancel()
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(heard.toList()).containsExactly("goodbye", "close 1000")
    }

    @Test
    fun `a link that has ended is not kept by the scope it was opened in`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = endedLink(scope)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (link.get() != null && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20)
        }
        assertThat(link.get()).isNull()
        scope.cancel()
    }

    /** A link opened in [scope], once it is open. */
    private fun open(scope: CoroutineScope, ended: CountDownLatch? = null): WebSocketLink {
        val opened = CountDownLatch(1)
        val link = WebSocketLink(scope, http, server.url("/").toString(), object : WebSocketLink.Listener {
            override fun onOpen(socket: WebSocketLink) = opened.countDown()

            override fun onClosed(code: Int, reason: String) {
                ended?.countDown()
            }
        })
        check(opened.await(5, TimeUnit.SECONDS)) { "the link did not open" }
        return link
    }

    /** A link opened in [scope] and closed again, which only a weak reference holds. */
    private fun endedLink(scope: CoroutineScope): WeakReference<WebSocketLink> {
        val ended = CountDownLatch(1)
        val link = open(scope, ended)
        link.close()
        check(ended.await(5, TimeUnit.SECONDS)) { "the link did not close" }
        return WeakReference(link)
    }
}
