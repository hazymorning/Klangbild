package io.github.hazymorning.klangbild.setup

import io.github.hazymorning.klangbild.render.QrCode
import io.github.hazymorning.klangbild.text.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The setup page served with plain sockets (no web framework): one short request per connection,
 * at most [MAX_CONNECTIONS] at a time, each with [REQUEST_DEADLINE_MS] to arrive and be answered.
 */
class SocketSetupServer(
    private val scope: CoroutineScope,
    private val handler: SetupHandler,
    private val addressProvider: () -> InetAddress? = ::guessLanAddress,
) : SetupServer {
    private val _info = MutableStateFlow<SetupInfo?>(null)
    override val info: StateFlow<SetupInfo?> = _info.asStateFlow()

    override val origin: String? get() = _info.value?.url

    private var socket: ServerSocket? = null

    /** The thread taking the connections: the port is shut only once it has let go of it. */
    private var acceptor: Thread? = null

    /** Connections whose request is still arriving: a stop closes them at once. */
    private val arriving = HashSet<Socket>()

    @Synchronized
    override fun start() {
        if (socket != null) return
        val address = addressProvider()
        val server = address?.let(::openSocket)
        if (address == null || server == null) {
            _info.value = SetupInfo(null, handler.code, null)
            return
        }
        val own = "http://${address.hostAddress}:${server.localPort}"
        socket = server
        handler.rotateCode()
        handler.onCodeChanged = { publish(own) }
        publish(own)
        val slots = Semaphore(MAX_CONNECTIONS)
        acceptor = thread(name = "Klangbild setup", isDaemon = true) {
            while (true) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                // One that came in as serving stopped is not served; too many at once, the newest waits for nothing.
                val taken = synchronized(arriving) { !server.isClosed && slots.tryAcquire() && arriving.add(client) }
                if (!taken) {
                    runCatching { client.close() }
                    if (server.isClosed) break else continue
                }
                // Not tied to this loop: an answer under way outlives a stop.
                scope.launch(Dispatchers.IO) {
                    try {
                        serve(client, own)
                    } finally {
                        slots.release()
                    }
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        val wasServing = socket != null
        handler.onCodeChanged = {}
        runCatching { socket?.close() }
        socket = null
        // The system still takes connections on a closed socket while a thread is blocked in its
        // accept(): the port is shut only once that thread has let go of it, which it does at once.
        acceptor?.join(STOP_WAIT_MS)
        acceptor = null
        synchronized(arriving) {
            arriving.forEach { runCatching { it.close() } }
            arriving.clear()
        }
        _info.value = null
        if (wasServing) scope.launch { handler.reset() }
    }

    private fun publish(url: String) {
        _info.value = SetupInfo(url, handler.code, QrCode.matrix("$url/?code=${handler.code}"))
    }

    /** The listening socket, on the LAN address only: the first of [PORTS] that is free, else any. */
    private fun openSocket(address: InetAddress): ServerSocket? {
        for (port in SetupServer.PORTS) {
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(address, port), BACKLOG)
                return candidate
            } catch (_: IOException) {
                // Port taken: try the next one.
                runCatching { candidate.close() }
            }
        }
        return runCatching { ServerSocket(0, BACKLOG, address) }.getOrNull()
    }

    /** Answers [client], which came to the page at [own] (also when serving stops meanwhile). */
    private suspend fun serve(client: Socket, own: String) {
        // However slowly a request arrives and is answered, it has this long; then its socket closes under it.
        val deadline = scope.launch {
            delay(REQUEST_DEADLINE_MS)
            runCatching { client.close() }
        }
        try {
            client.use { s ->
                val texts = handler.texts()
                val response = try {
                    s.soTimeout = READ_TIMEOUT_MS
                    val req = MiniHttp.read(s.getInputStream().asSource().buffered())
                    // It has arrived: from now on a stop lets it be answered (one cut off just now is not).
                    if (!synchronized(arriving) { arriving.remove(client) }) return
                    SetupServer.refusal(req, own, texts) ?: handler.handle(req)
                } catch (_: MiniHttp.BadRequest) {
                    MiniHttp.Response.text(400, texts[Strings.setupErrorRequest])
                } catch (_: IOException) {
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    MiniHttp.Response.json(500, buildJsonObject {
                        put("ok", false)
                        put("message", texts[Strings.setupErrorInternal])
                    }.toString())
                }
                runCatching { MiniHttp.write(s.getOutputStream().asSink().buffered(), response) }
            }
        } finally {
            deadline.cancel()
            synchronized(arriving) { arriving -= client }
        }
    }

    companion object {
        /** Connections served at once; a phone filling in one form needs one or two. */
        const val MAX_CONNECTIONS = 4

        /** Connections waiting to be accepted. */
        private const val BACKLOG = 8

        /** One read may wait this long. */
        const val READ_TIMEOUT_MS = 5_000

        /** The whole request may take this long. */
        const val REQUEST_DEADLINE_MS = 15_000L

        /** How long a stop waits for the thread taking the connections to let go of the port. */
        private const val STOP_WAIT_MS = 2_000L

        /** First private IPv4 address of an up, non-loopback interface (Ethernet or Wi-Fi). */
        fun guessLanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()
    }
}
