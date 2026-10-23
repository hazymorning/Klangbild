package io.github.hazymorning.klangbild.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.URLProtocol
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.concurrent.Volatile

/**
 * A WebSocket to [url] (`ws`, `wss`, or `http` and `https` for them) over [http], opened at
 * once in [scope]. What happens on it reaches [listener] as it happens, on the thread that
 * receives it, so a message's arrival can be timed in [Listener.onBinary]. It ends once, with
 * [Listener.onClosed] or [Listener.onFailure].
 */
class WebSocketLink(scope: CoroutineScope, http: HttpClient, url: String, private val listener: Listener) {
    interface Listener {
        /** The connection is open: messages can be sent on [socket]. */
        fun onOpen(socket: WebSocketLink) = Unit

        fun onText(text: String) = Unit

        fun onBinary(bytes: ByteArray) = Unit

        /** The server closed the connection with [code] and [reason]. */
        fun onClosed(code: Int, reason: String) = Unit

        /** The connection failed, could not be made or was canceled ([cancel]). */
        fun onFailure(error: Throwable) = Unit
    }

    @Volatile private var session: DefaultClientWebSocketSession? = null
    private val ended = atomic(false)

    // Unconfined: messages are handed on in the thread that receives them, not after a hop.
    private val job = scope.launch(Dispatchers.Unconfined) {
        try {
            http.webSocket({
                address(url)
                this.url.protocol = when (this.url.protocol) {
                    URLProtocol.HTTP -> URLProtocol.WS
                    URLProtocol.HTTPS -> URLProtocol.WSS
                    else -> this.url.protocol
                }
            }) {
                session = this
                listener.onOpen(this@WebSocketLink)
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> listener.onText(frame.readText())
                        is Frame.Binary -> listener.onBinary(frame.data)
                        else -> Unit
                    }
                }
                val reason = closeReason.await()
                if (ended.compareAndSet(expect = false, update = true)) {
                    listener.onClosed(reason?.code?.toInt() ?: NO_STATUS, reason?.message.orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (ended.compareAndSet(expect = false, update = true)) listener.onFailure(e)
        } finally {
            session = null
        }
    }

    /** Queues [text]; false when the connection is not open (any more). */
    fun send(text: String): Boolean = session?.outgoing?.trySend(Frame.Text(text))?.isSuccess == true

    /** Queues [bytes]; false when the connection is not open (any more). */
    fun send(bytes: ByteArray): Boolean = session?.outgoing?.trySend(Frame.Binary(true, bytes))?.isSuccess == true

    /** Closes the connection normally, after whatever is queued; one not open yet is dropped. */
    fun close() {
        val s = session
        if (s == null || !s.outgoing.trySend(Frame.Close(CloseReason(NORMAL_CLOSURE, ""))).isSuccess) cancel()
    }

    /**
     * Gives the connection up at once: the listener hears of it as a failure, unless it has
     * ended already, and the socket closes on its own.
     */
    fun cancel() {
        job.cancel()
        if (ended.compareAndSet(expect = false, update = true)) listener.onFailure(IOException("Canceled"))
    }

    private companion object {
        const val NORMAL_CLOSURE: Short = 1000

        /** The code a close frame without one stands for. */
        const val NO_STATUS = 1005
    }
}
