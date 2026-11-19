package io.github.hazymorning.klangbild.data.ma

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.data.httpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What Music Assistant keeps about the artists of the songs on stage and up next, against a
 * stand-in Music Assistant: what is asked, how often, and what is kept.
 */
class MaBiosTest {
    private val asked = CopyOnWriteArrayList<String>()

    /** What Music Assistant keeps about an artist (by its key), as the opening of its article; by default nothing. */
    private val texts = ConcurrentHashMap<String, String>()

    /** Artists whose call fails. */
    private val failing = ConcurrentHashMap.newKeySet<String>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                assertThat(body["command"]!!.jsonPrimitive.content).isEqualTo("music/artists/get")
                val args = body["args"]!!.jsonObject
                val key = args["provider_instance_id_or_domain"]!!.jsonPrimitive.content + "/" + args["item_id"]!!.jsonPrimitive.content
                asked += key
                if (key in failing) return MockResponse.Builder().code(500).body("{}").build()
                val description = texts[key]?.let { JsonPrimitive(it).toString() } ?: "null"
                return MockResponse.Builder().body("""{"item_id":"${key.substringAfter('/')}","name":"Artist","metadata":{"description":$description}}""").build()
            }
        }
        start()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = server.url("/").toString().trimEnd('/')
    private var clock = 1_000_000L
    private val changes = AtomicInteger()
    private val bios = MaBios(MaClient(httpClient(OkHttp.create()), base, "ma-token"), scope, now = { clock }) { changes.incrementAndGet() }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.close()
    }

    /** A queue item's song by the artist [artist] of [provider], as Music Assistant gives it. */
    private fun song(n: Int, artist: String, provider: String = "library"): QueueSong {
        val item = Json.parseToJsonElement(
            """{"media_type":"track","name":"Lied $n","uri":"library://track/$n","artists":[{"item_id":"$artist","provider":"$provider","name":"Artist $artist"}]}""",
        ).jsonObject
        return QueueSong("library://track/$n", item, null, null)
    }

    private suspend fun until(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    @Test
    fun `the artist of the song that plays is asked about first, and each artist once`() = runBlocking<Unit> {
        texts["library/7"] = "Lumen Drive is a German synthwave duo. It was formed in 2016."
        texts["spotify/x"] = "Theo Lind is a Swedish pianist."
        // The album's next song is by the same artist: asked once.
        bios.want(listOf(song(1, "7"), song(2, "7")))
        until { bios.known(song(1, "7")) != null }
        assertThat(bios.known(song(1, "7"))!!.bio!!.paragraphs.single()).containsExactly("Lumen Drive is a German synthwave duo.", "It was formed in 2016.")
        bios.want(listOf(song(2, "7"), song(3, "x", provider = "spotify")))
        until { bios.known(song(3, "x", provider = "spotify")) != null }
        assertThat(asked).containsExactly("library/7", "spotify/x")
        assertThat(changes.get()).isEqualTo(2)
    }

    @Test
    fun `a song without an artist asks nothing`() = runBlocking<Unit> {
        val item = Json.parseToJsonElement("""{"media_type":"track","name":"Lied","uri":"library://track/9"}""").jsonObject
        val song = QueueSong("library://track/9", item, null, null)
        bios.want(listOf(song))
        delay(100)
        assertThat(asked).hasSize(0)
        assertThat(bios.known(song)).isNull()
    }

    @Test
    fun `an artist without a text is asked again a while later, a few times, as Music Assistant may still look`() = runBlocking<Unit> {
        bios.want(listOf(song(1, "7")))
        until { bios.known(song(1, "7")) != null }
        assertThat(bios.known(song(1, "7"))!!.bio).isNull()
        // Too soon: not again.
        bios.want(listOf(song(1, "7")))
        delay(100)
        assertThat(asked).hasSize(1)
        // Later Music Assistant has found it.
        texts["library/7"] = "Lumen Drive is a German synthwave duo."
        clock += MaBios.AGAIN_MS
        bios.want(listOf(song(1, "7")))
        until { bios.known(song(1, "7"))?.bio != null }
        assertThat(asked).hasSize(2)
        // Once known, never again.
        clock += 10 * MaBios.AGAIN_MS
        bios.want(listOf(song(1, "7")))
        delay(100)
        assertThat(asked).hasSize(2)
    }

    @Test
    fun `an artist Music Assistant knows nothing about is asked no more than a few times`() = runBlocking<Unit> {
        repeat(MaBios.ASKS + 2) {
            bios.want(listOf(song(1, "7")))
            // Its answer kept, not only its call made.
            until { bios.known(song(1, "7"))?.asks == minOf(it + 1, MaBios.ASKS) }
            clock += MaBios.AGAIN_MS
        }
        delay(100)
        assertThat(asked).hasSize(MaBios.ASKS)
    }

    @Test
    fun `a failed call is made again much later, and what was known meanwhile stays`() = runBlocking<Unit> {
        failing += "library/7"
        bios.want(listOf(song(1, "7")))
        until { bios.known(song(1, "7")) != null }
        assertThat(bios.known(song(1, "7"))!!.bio).isNull()
        clock += MaBios.AGAIN_MS
        bios.want(listOf(song(1, "7")))
        delay(100)
        assertThat(asked).hasSize(1)
        failing.clear()
        texts["library/7"] = "Lumen Drive is a German synthwave duo."
        clock += MaBios.RETRY_MS
        bios.want(listOf(song(1, "7")))
        until { bios.known(song(1, "7"))?.bio != null }
        assertThat(bios.known(song(1, "7"))!!.bio).isNotNull()
    }
}
