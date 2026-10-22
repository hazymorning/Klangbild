package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.sendspin.ClockMap
import io.github.hazymorning.klangbild.sendspin.Outgoing
import io.github.hazymorning.klangbild.sendspin.TimeFilter
import io.github.hazymorning.klangbild.sendspin.int64
import io.github.hazymorning.klangbild.sound.SoundAnalyzer
import io.github.hazymorning.klangbild.sound.SoundFeed
import io.github.hazymorning.klangbild.sound.SoundFrame
import io.github.hazymorning.klangbild.sound.SoundTiming
import io.github.hazymorning.klangbild.sound.SoundTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The sound of another Music Assistant speaker, from Music Assistant's MilkDrop visualizer plugin
 * (experimental, off by default): its relay sends the decoded music of a player's queue as mono
 * windows of 1024 samples, each stamped with when its last sample is heard on the server's own
 * clock, and the beats Music Assistant knows. Each window is measured as this TV's own playback
 * is and timed through a clock filter of its own, so the times are close (as close as Music
 * Assistant knows the player's position), not exact. Without the plugin, or while it cannot be
 * reached, nothing is known.
 *
 * It listens only while it is read and a player is followed ([follow]). The relay's protocol is
 * the 2.10.7 plugin's (`relay.py`, `tap.py`); the change drafted for 2.11 keeps its wire as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MilkdropSound(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Music Assistant's address (`http://host:8095`). */
    private val baseUrl: String,
    private val token: String,
    private val nowUs: () -> Long = { System.nanoTime() / 1_000 },
    private val backoff: () -> Backoff = { Backoff(RETRY_MS, LAST_RETRY_MS) },
) : SoundFeed {
    private val timeline = SoundTimeline()
    private var readers = 0
    private var player: String? = null
    private var job: Job? = null

    override fun read(nowUs: Long, out: SoundFrame): Boolean = timeline.read(nowUs, out)

    @Synchronized
    override fun reading(on: Boolean) {
        readers = (readers + if (on) 1 else -1).coerceAtLeast(0)
        listen()
    }

    /** The Music Assistant player whose sound is wanted (its queue's id), or none. */
    @Synchronized
    fun follow(playerId: String?) {
        if (playerId == player) return
        player = playerId
        listen()
    }

    private fun listen() {
        val id = player.takeIf { readers > 0 }
        job?.cancel()
        job = null
        timeline.clear()
        if (id != null) job = scope.launch { connect(id) }
    }

    /** One relay connection after another while wanted, waiting longer after each that heard nothing. */
    private suspend fun connect(playerId: String) {
        val wait = backoff()
        while (true) {
            if (Relay().run(playerId)) wait.reset()
            timeline.clear()
            delay(wait.nextDelayMs())
        }
    }

    private sealed interface Event {
        class Text(val text: String) : Event
        class Binary(val bytes: ByteString) : Event
        object Closed : Event
    }

    /** One connection to the relay, with its own clock and measuring. */
    private inner class Relay {
        private val filter = TimeFilter()
        private var clock: ClockMap? = null
        private val frame = SoundFrame().apply { timing = SoundTiming.CLOSE }
        private val samples = FloatArray(WINDOW)
        private var analyzer: SoundAnalyzer? = null
        private var rate = 0
        private var lastStampUs = Long.MIN_VALUE

        /** Until the connection ends; whether it heard music. */
        suspend fun run(playerId: String): Boolean {
            val events = Channel<Event>(Channel.UNLIMITED)
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment(PATH).addQueryParameter("player", playerId).build()
            val socket = http.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(buildJsonObject { put("type", "auth"); put("token", token) }.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        events.trySend(Event.Text(text))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        events.trySend(Event.Binary(bytes))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(NORMAL_CLOSURE, null)
                        events.trySend(Event.Closed)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        events.trySend(Event.Closed)
                    }
                },
            )
            var heard = false
            var authorized = false
            var nextTimeAt = Long.MAX_VALUE
            try {
                while (true) {
                    val event = select<Event?> {
                        events.onReceive { it }
                        if (nextTimeAt != Long.MAX_VALUE) onTimeout(((nextTimeAt - nowUs()) / 1_000).coerceAtLeast(0)) { null }
                    }
                    when (event) {
                        null -> {
                            nextTimeAt = Long.MAX_VALUE
                            socket.send(timeMessage())
                        }
                        Event.Closed -> return heard
                        is Event.Text -> {
                            val msg = runCatching { Json.parseToJsonElement(event.text) as? JsonObject }.getOrNull() ?: continue
                            when (msg.string("type")) {
                                "auth_ok" -> {
                                    authorized = true
                                    socket.send(timeMessage())
                                }
                                // A burst at first, then one now and then to follow the drift.
                                "server/time" -> nextTimeAt = nowUs() + if (time(msg) && filter.count < BURST) 0 else TIME_EVERY_US
                                "stream/clear", "stream/end" -> cleared()
                                "error" -> return heard
                            }
                        }
                        is Event.Binary -> if (authorized && binary(event.bytes.toByteArray())) heard = true
                    }
                }
            } finally {
                socket.close(NORMAL_CLOSURE, null)
            }
        }

        /** The relay takes Sendspin's `client/time` as it is. */
        private fun timeMessage() = Outgoing.clientTime(nowUs()).encode()

        /** A `server/time` answer taken into the clock filter; whether it was taken. */
        private fun time(msg: JsonObject): Boolean {
            val received = nowUs()
            val p = msg["payload"] as? JsonObject ?: return false
            fun long(key: String) = (p[key] as? JsonPrimitive)?.longOrNull
            val sent = long("client_transmitted") ?: return false
            val serverReceived = long("server_received") ?: return false
            val serverSent = long("server_transmitted") ?: return false
            if (!filter.update(sent, serverReceived, serverSent, received)) return false
            if (filter.count >= SYNCED_AFTER) clock = filter.snapshot()
            return true
        }

        /** A waveform window or a beat; whether it was music that could be timed. */
        private fun binary(bytes: ByteArray): Boolean {
            val c = clock ?: return false
            if (bytes.size < HEADER) return false
            val stampUs = bytes.int64(1)
            return when (bytes[0].toInt() and 0xff) {
                WAVEFORM -> bytes.size >= HEADER + WINDOW && window(bytes, c.clientTime(stampUs), stampUs)
                BEAT -> {
                    if (bytes.size > HEADER) timeline.beat(c.clientTime(stampUs), bytes[HEADER].toInt() and 1 == 1)
                    true
                }
                else -> false
            }
        }

        /** One window whose last sample is heard at [lastHeardUs] ([stampUs] on the server's clock); whether it was measured. */
        private fun window(bytes: ByteArray, lastHeardUs: Long, stampUs: Long): Boolean {
            // The relay names no sample rate: windows follow each other without a gap, so the time
            // between two tells it, and it is one of the rates music comes in.
            val gap = stampUs - lastStampUs
            lastStampUs = stampUs
            if (gap in 1..MAX_GAP_US) {
                val measured = WINDOW * 1_000_000.0 / gap
                val nearest = RATES.minBy { abs(it - measured) }
                if (nearest != rate) {
                    rate = nearest
                    analyzer = SoundAnalyzer(nearest)
                }
            }
            val a = analyzer ?: return false
            for (i in 0 until WINDOW) samples[i] = ((bytes[HEADER + i].toInt() and 0xff) - ZERO) / ZERO.toFloat()
            a.analyze(samples, 0, frame)
            frame.timeUs = lastHeardUs - (WINDOW / 2 * 1_000_000.0 / rate).roundToLong()
            timeline.push(frame)
            return true
        }

        /** The relay dropped what it had sent ahead (a new song, a jump, a pause). */
        private fun cleared() {
            timeline.clear()
            analyzer?.reset()
            lastStampUs = Long.MIN_VALUE
        }
    }

    companion object {
        const val PATH = "milkdrop_visualizer"
        private const val NORMAL_CLOSURE = 1000

        private const val WAVEFORM = 22
        private const val BEAT = 17

        /** The type and the timestamp. */
        private const val HEADER = 9
        private const val WINDOW = 1024

        /** A sample of 128 is silence. */
        private const val ZERO = 128

        /** Windows further apart than this do not follow each other (a pause, a new song). */
        private const val MAX_GAP_US = 50_000L

        private val RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)

        /** Time messages: a burst of this many at first, then one every few seconds. */
        private const val BURST = 8
        private const val TIME_EVERY_US = 5_000_000L

        /** The clock is used once this many measurements are in. */
        private const val SYNCED_AFTER = 3

        const val RETRY_MS = 5_000L
        const val LAST_RETRY_MS = 5 * 60_000L
    }
}
