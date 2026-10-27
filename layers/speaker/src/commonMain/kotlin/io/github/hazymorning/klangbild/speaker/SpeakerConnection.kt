package io.github.hazymorning.klangbild.speaker

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.data.WebSocketLink
import io.github.hazymorning.klangbild.data.WebUrl.Companion.toWebUrlOrNull
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.sendspin.Session
import io.github.hazymorning.klangbild.sendspin.SessionEnd
import io.github.hazymorning.klangbild.sendspin.Transport
import io.github.hazymorning.klangbild.sound.monotonicMicros
import io.ktor.client.HttpClient
import kotlinx.atomicfu.atomic
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
import kotlin.concurrent.Volatile

/**
 * The speaker's connection to Music Assistant (client-initiated, connection.md): a WebSocket to
 * [url], a [Session] on it, time bursts while it lasts, and a new attempt after a pause when it
 * ends. A server too old for this protocol, or one that refuses the speaker, is tried again
 * only now and then, and [link] says so.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeakerConnection(
    /** Pings the server every [PING_MS] (messaging.md: the liveness mechanism). */
    private val http: HttpClient,
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
                SessionEnd.LOST to (e.message ?: e::class.simpleName.orEmpty())
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
            // Only a wake-up during the pause counts, not one from while the session ran.
            wakeUp.tryReceive()
            select<Unit> {
                wakeUp.onReceive { }
                onTimeout(waitMs) { }
            }
        }
    }

    /** One connection, from opening the socket to the session's end. */
    private suspend fun runSession(connected: () -> Unit): Pair<SessionEnd, String> = coroutineScope {
        val done = CompletableDeferred<Pair<SessionEnd, String>>()
        // Set by whichever comes first: the socket opening on the connection's thread, or its making returning here.
        val link = atomic<WebSocketLink?>(null)
        val transport = object : Transport {
            override fun sendText(text: String): Boolean = link.value?.send(text) == true
            override fun sendBinary(bytes: ByteArray): Boolean = link.value?.send(bytes) == true
            override fun close() {
                // A close frame after whatever is queued (a goodbye), never cutting it off.
                link.value?.close()
            }
        }
        val s = newSession(transport) { end, detail -> done.complete(end to detail) }
        session = s
        val opened = WebSocketLink(scope, http, url, object : WebSocketLink.Listener {
            override fun onOpen(socket: WebSocketLink) {
                link.compareAndSet(null, socket)
                s.start()
            }

            override fun onText(text: String) = s.onText(text)

            override fun onBinary(bytes: ByteArray) {
                // The receive time is taken as the message arrives (messaging.md, "Receive timestamps").
                val arrival = monotonicMicros()
                s.onBinary(bytes, arrival)
            }

            override fun onClosed(code: Int, reason: String) = s.onClosed(byServer = true)

            override fun onFailure(error: Throwable) = s.onClosed(byServer = false)
        })
        link.compareAndSet(null, opened)
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
            val host = maUrl.toWebUrlOrNull()?.host ?: return null
            val literal = if (host.contains(':')) "[$host]" else host
            return "ws://$literal:$PORT$PATH"
        }

        const val PORT = 8927
        const val PATH = "/sendspin"

        /** How often the client pings the server, which keeps the connection known to be alive. */
        const val PING_MS = 15_000L
    }
}
