package io.github.hazymorning.klangbild.speaker

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.sendspin.Session
import io.github.hazymorning.klangbild.sendspin.SessionEnd
import io.github.hazymorning.klangbild.sendspin.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The speaker's connection to Music Assistant (client-initiated, connection.md): a WebSocket to
 * [url], a [Session] on it, time bursts while it lasts, and a new attempt after a pause when it
 * ends. A server too old for this protocol, or one that refuses the speaker, is tried again
 * only now and then, and [link] says so.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeakerConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val url: String,
    /** A new session for a connection; it reports its end through the given callback. */
    private val newSession: (Transport, (SessionEnd, String) -> Unit) -> Session,
    private val backoffFactory: () -> Backoff = { Backoff(maxMs = 60_000) },
    /** How long a server too old for the protocol, or one that refused, waits for the next try. */
    private val laterMs: Long = LATER_MS,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val fastBurstMs: Long = FAST_BURST_MS,
    private val burstMs: Long = BURST_MS,
) {
    private val _link = MutableStateFlow(SpeakerLink.CONNECTING)
    val link: StateFlow<SpeakerLink> = _link.asStateFlow()

    private var loop: Job? = null
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var session: Session? = null

    fun start() {
        if (loop != null) return
        loop = scope.launch { runLoop() }
    }

    /**
     * Ends the connection, saying goodbye with [reason]: `shutdown` when the speaker is switched
     * off (Music Assistant lets the speaker go at once), `restart` when it is only going away
     * for a while (Music Assistant keeps it ready for a few minutes).
     */
    fun stop(reason: String) {
        session?.goodbye(reason)
        loop?.cancel()
        loop = null
        session = null
        _link.value = SpeakerLink.CONNECTING
    }

    /** Skips the pause before the next attempt (the network came back). */
    fun retryNow() {
        wakeUp.trySend(Unit)
    }

    private suspend fun runLoop() {
        val backoff = backoffFactory()
        while (scope.isActive) {
            _link.value = SpeakerLink.CONNECTING
            val (end, detail) = try {
                runSession { backoff.reset() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SessionEnd.LOST to (e.message ?: e.javaClass.simpleName)
            } finally {
                session = null
            }
            val waitMs = when (end) {
                SessionEnd.GOODBYE -> return
                SessionEnd.OLD_SERVER -> {
                    _link.value = SpeakerLink.TOO_OLD
                    laterMs
                }
                SessionEnd.NEW_SERVER -> {
                    _link.value = SpeakerLink.TOO_NEW
                    laterMs
                }
                SessionEnd.REFUSED, SessionEnd.UNAUTHORIZED, SessionEnd.UNPAIRED -> {
                    _link.value = SpeakerLink.REFUSED
                    laterMs
                }
                SessionEnd.BROKEN, SessionEnd.LOST -> {
                    _link.value = SpeakerLink.UNREACHABLE
                    backoff.nextDelayMs()
                }
            }
            select<Unit> {
                wakeUp.onReceive { }
                onTimeout(waitMs) { }
            }
        }
    }

    /** One connection, from opening the socket to the session's end. */
    private suspend fun runSession(connected: () -> Unit): Pair<SessionEnd, String> = coroutineScope {
        val done = CompletableDeferred<Pair<SessionEnd, String>>()
        // Set by whichever comes first: the socket opening on OkHttp's thread, or newWebSocket returning here.
        val socket = AtomicReference<WebSocket?>(null)
        val transport = object : Transport {
            override fun sendText(text: String): Boolean = socket.get()?.send(text) == true
            override fun sendBinary(bytes: ByteArray): Boolean = socket.get()?.send(bytes.toByteString()) == true
            override fun close() {
                // A close frame after whatever is queued (a goodbye), never cutting it off.
                socket.get()?.close(NORMAL_CLOSURE, null)
            }
        }
        val s = newSession(transport) { end, detail -> done.complete(end to detail) }
        session = s
        val request = Request.Builder().url(url).build()
        val opened = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.compareAndSet(null, webSocket)
                s.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                s.onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The receive time is taken as the message arrives (messaging.md, "Receive timestamps").
                val arrival = System.nanoTime() / 1_000
                s.onBinary(bytes.toByteArray(), arrival)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                s.onClosed(byServer = true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = s.onClosed(byServer = true)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = s.onClosed(byServer = false)
        })
        socket.compareAndSet(null, opened)
        try {
            // The handshake, the hellos and the activation within a while, or the attempt ends.
            val active = withTimeoutOrNull(handshakeTimeoutMs) {
                while (!s.playerRole && !done.isCompleted && !s.activated) delay(POLL_MS)
                true
            }
            if (active == null) {
                transport.close()
                s.onClosed(byServer = false)
            } else if (!done.isCompleted) {
                connected()
                _link.value = SpeakerLink.CONNECTED
                // Bursts come quickly until the clock has settled, then every few seconds
                // (messaging.md: never more than ten seconds apart).
                launch {
                    while (!done.isCompleted) {
                        delay(if (s.synced) burstMs else fastBurstMs)
                        s.burst()
                    }
                }
            }
            done.await()
        } finally {
            transport.close()
            coroutineContext[Job]?.cancelChildren()
        }
    }

    private fun Job.cancelChildren() = children.forEach { it.cancel() }

    companion object {
        /** A server too old, or one that refused: tried again after this long. */
        const val LATER_MS = 10 * 60_000L

        /** connection.md suggests 30 s for each step of the handshake; this covers them all. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val FAST_BURST_MS = 250L
        const val BURST_MS = 5_000L
        private const val POLL_MS = 50L

        /** The address of Music Assistant's Sendspin server for clients that connect themselves. */
        fun urlFor(maUrl: String): String? {
            val host = maUrl.toHttpUrlOrNull()?.host ?: return null
            val literal = if (host.contains(':')) "[$host]" else host
            return "ws://$literal:$PORT$PATH"
        }

        const val PORT = 8927
        private const val NORMAL_CLOSURE = 1000
        const val PATH = "/sendspin"

        /** Pings keep the connection known to be alive (messaging.md: the liveness mechanism). */
        fun client(http: OkHttpClient): OkHttpClient = http.newBuilder().pingInterval(15, TimeUnit.SECONDS).build()
    }
}
