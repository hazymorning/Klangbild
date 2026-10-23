package io.github.hazymorning.klangbild.data.ma

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.sound.SoundFrame
import io.github.hazymorning.klangbild.sound.SoundTiming
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test

/** The MilkDrop plugin's relay as a sound feed, against a stand-in relay that speaks its 2.10.7 protocol. */
class MilkdropSoundTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val said = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile private var relay: WebSocket? = null
    @Volatile private var plugin = true
    @Volatile private var closed = false
    private fun nowUs() = System.nanoTime() / 1_000

    /** The relay's clock runs this far ahead of the TV's. */
    private val offsetUs = 7_000_000_000L

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath + "?" + request.url.encodedQuery
                if (!plugin) return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        relay = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        said += text
                        val msg = Json.parseToJsonElement(text).jsonObject
                        when (msg["type"]!!.jsonPrimitive.content) {
                            "auth" -> webSocket.send(
                                if (msg["token"]!!.jsonPrimitive.content == "ma-token") """{"type": "auth_ok"}""" else """{"type":"error","message":"bad"}""",
                            )
                            "client/time" -> {
                                val sent = msg["payload"]!!.jsonObject["client_transmitted"]!!.jsonPrimitive.long
                                val at = nowUs() + offsetUs
                                webSocket.send("""{"type":"server/time","payload":{"client_transmitted":$sent,"server_received":$at,"server_transmitted":$at}}""")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closed = true
                        webSocket.close(1000, null)
                    }
                }).build()
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun feed() = MilkdropSound(httpClient(OkHttp.create()), scope, server.url("/").toString().trimEnd('/'), "ma-token")

    /** One window of a 1 kHz tone at 48 kHz whose last sample is heard at [lastHeardUs] on the relay's clock. */
    private fun window(lastHeardUs: Long, amplitude: Double, index: Int) = ByteBuffer.allocate(9 + 1024).apply {
        put(22)
        putLong(lastHeardUs)
        for (i in 0 until 1024) {
            val n = index * 1024 + i
            put((amplitude * sin(2 * PI * 1_000 * n / 48_000) * 127 + 128).roundToInt().toByte())
        }
    }.array().toByteString()

    @Test
    fun `windows heard on the relay's clock are measured and read at the TV's time, close`() = runBlocking {
        val sound = feed()
        sound.reading(true)
        sound.follow("up1a2b3c4d")
        withTimeout(10_000) { while (said.count { "client/time" in it } < 8) delay(20) }
        assertThat(paths.single()).isEqualTo("/milkdrop_visualizer?player=up1a2b3c4d")
        assertThat(said.first()).isEqualTo("""{"type":"auth","token":"ma-token"}""")
        // Half a second of music from 0.5 s from now, and a beat in it.
        val startUs = nowUs() + 500_000L
        val windowUs = 1024 * 1_000_000L / 48_000
        for (k in 0 until 24) relay!!.send(window(startUs + offsetUs + (k + 1) * windowUs, 0.5, k))
        relay!!.send(ByteBuffer.allocate(10).put(17).putLong(startUs + offsetUs + 100_000L).put(1).array().toByteString())
        val out = SoundFrame()
        val at = startUs + 300_000L
        // The beat comes after the windows.
        withTimeout(5_000) { while (!sound.read(at, out) || out.beatUs == SoundFrame.NO_BEAT) delay(20) }
        assertThat(out.timing).isEqualTo(SoundTiming.CLOSE)
        assertThat(out.loudness).isCloseTo(0.9f, 0.03f)
        // Its time is within a window of the moment asked for, the clock mapped to a few milliseconds.
        assertThat(at - out.timeUs).isBetween(-5_000L, windowUs + 5_000L)
        assertThat(out.beatUs - startUs).isBetween(95_000L, 105_000L)
        assertThat(out.downbeat).isTrue()
        // A new song: what was sent ahead goes.
        relay!!.send("""{"type": "stream/clear"}""")
        withTimeout(5_000) { while (sound.read(at, out)) delay(20) }
        assertThat(out.timing).isEqualTo(SoundTiming.NONE)
        // Nobody reads: the relay is left.
        sound.reading(false)
        withTimeout(5_000) { while (!closed) delay(20) }
    }

    @Test
    fun `without the plugin nothing is known, and nothing is asked while nobody reads`() = runBlocking {
        val sound = feed()
        sound.follow("up1a2b3c4d")
        delay(300)
        assertThat(paths).isEmpty()
        plugin = false
        sound.reading(true)
        withTimeout(5_000) { while (paths.isEmpty()) delay(20) }
        val out = SoundFrame()
        assertThat(sound.read(nowUs(), out)).isFalse()
        assertThat(out.timing).isEqualTo(SoundTiming.NONE)
        sound.follow(null)
    }
}
