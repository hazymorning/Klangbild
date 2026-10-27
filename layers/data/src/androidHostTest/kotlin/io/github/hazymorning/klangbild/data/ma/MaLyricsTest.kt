package io.github.hazymorning.klangbild.data.ma

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The words of the songs on stage and up next, against a stand-in Music Assistant: what is asked,
 * in which order, how often, and what is kept or dropped.
 */
class MaLyricsTest {
    private val asked = CopyOnWriteArrayList<String>()

    /** How Music Assistant answers for a song (by its URI); by default it knows no words. */
    private val answers = ConcurrentHashMap<String, () -> MockResponse>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                assertThat(body["command"]!!.jsonPrimitive.content).isEqualTo("metadata/get_track_lyrics")
                val uri = body["args"]!!.jsonObject["track"]!!.jsonObject["uri"]!!.jsonPrimitive.content
                asked += uri
                return answers[uri]?.invoke() ?: MockResponse.Builder().body("[null,null]").build()
            }
        }
        start()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = server.url("/").toString().trimEnd('/')
    private var clock = 1_000_000L
    private val changes = AtomicInteger()
    private val lyrics = lyrics(MaClient(httpClient(OkHttp.create()), base, "ma-token"))

    private fun lyrics(client: MaClient) = MaLyrics(client, scope, now = { clock }) { changes.incrementAndGet() }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val lrc = "[00:05.00]Erste Zeile\n[00:09.00]Zweite Zeile"

    /** A queue item's song, as Music Assistant gives it: its URI, and the words it brings, if any. */
    private fun song(n: Int, lyrics: String? = null, lrc: String? = null): QueueSong {
        val item = Json.parseToJsonElement("""{"media_type":"track","name":"Lied $n","uri":"library://track/$n","provider":"library","item_id":"$n"}""").jsonObject
        return QueueSong("library://track/$n", item, lyrics, lrc)
    }

    /** Music Assistant's answer: the plain words and the synced ones. */
    private fun answer(lyrics: String?, lrc: String?) = MockResponse.Builder().body("[${quoted(lyrics)},${quoted(lrc)}]").build()

    private fun quoted(s: String?) = s?.let { JsonPrimitive(it).toString() } ?: "null"

    private suspend fun until(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    @Test
    fun `a song that brings its words needs no call`() = runBlocking {
        lyrics.want(listOf(song(1, lrc = lrc), song(2, lyrics = "Nur Worte\nohne Zeiten")))
        assertThat(lyrics.known("library://track/1")!!.lyrics!!.lines).hasSize(2)
        // Plain words are no synced words.
        assertThat(lyrics.known("library://track/2")!!.lyrics).isNull()
        delay(100)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `the song that plays is asked about first, then the one up next, one at a time`() = runBlocking {
        val hold = CountDownLatch(1)
        answers["library://track/1"] = { hold.await(5, TimeUnit.SECONDS); answer(null, lrc) }
        answers["library://track/2"] = { answer(lrc, null) }
        lyrics.want(listOf(song(1), song(2)))
        until { asked.size == 1 }
        delay(100)
        assertThat(asked).containsExactlyInAnyOrder("library://track/1")
        hold.countDown()
        until { lyrics.known("library://track/2") != null }
        assertThat(asked).containsExactly("library://track/1", "library://track/2")
        assertThat(lyrics.known("library://track/1")!!.lyrics!!.lines.map { it.text }).containsExactly("Erste Zeile", "Zweite Zeile")
        // Earlier versions may send the synced words in the plain field.
        assertThat(lyrics.known("library://track/2")!!.lyrics).isNotNull()
        // Kept by the song's URI: asked once.
        lyrics.want(listOf(song(2), song(1)))
        delay(100)
        assertThat(asked).hasSize(2)
    }

    @Test
    fun `a call for a song that neither plays nor comes next is canceled, and its answer dropped`() = runBlocking {
        val hold = CountDownLatch(1)
        answers["library://track/1"] = { hold.await(5, TimeUnit.SECONDS); answer(null, lrc) }
        answers["library://track/3"] = { answer(null, lrc) }
        lyrics.want(listOf(song(1), song(2)))
        until { asked.size == 1 }
        // Skipped to something else: its song is asked about at once.
        lyrics.want(listOf(song(3), song(4)))
        until { lyrics.known("library://track/3") != null }
        hold.countDown()
        until { asked.size >= 3 }
        delay(100)
        assertThat(asked.take(3)).containsExactly("library://track/1", "library://track/3", "library://track/4")
        assertThat(lyrics.known("library://track/1")).isNull()
    }

    @Test
    fun `an answer may take longer than any other call`() = runBlocking {
        // Longer than any other command may take, and than the client waits for the next bytes.
        val http = httpClient(OkHttp.create { config { readTimeout(200, TimeUnit.MILLISECONDS) } })
        val patient = lyrics(MaClient(http, base, "ma-token", callTimeoutMs = 200, lyricsTimeoutMs = 3_000))
        answers["library://track/1"] = { MockResponse.Builder().headersDelay(600, TimeUnit.MILLISECONDS).body("[null,${quoted(lrc)}]").build() }
        patient.want(listOf(song(1)))
        until { patient.known("library://track/1") != null }
        assertThat(patient.known("library://track/1")!!.lyrics).isNotNull()
        assertThat(changes.get()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `a version without the call is asked once, and songs without words of their own have none`() = runBlocking {
        answers["library://track/1"] = { MockResponse.Builder().code(400).body("Invalid Command: metadata/get_track_lyrics").build() }
        lyrics.want(listOf(song(1), song(2)))
        until { lyrics.known("library://track/2") != null }
        assertThat(lyrics.known("library://track/1")!!.lyrics).isNull()
        assertThat(lyrics.known("library://track/2")!!.lyrics).isNull()
        lyrics.want(listOf(song(3, lrc = lrc), song(4)))
        delay(100)
        assertThat(asked).containsExactlyInAnyOrder("library://track/1")
        assertThat(lyrics.known("library://track/3")!!.lyrics).isNotNull()
        assertThat(lyrics.known("library://track/4")!!.lyrics).isNull()
    }

    @Test
    fun `a failed call counts as none, and is made again only after a while`() = runBlocking {
        answers["library://track/1"] = { MockResponse.Builder().code(500).build() }
        lyrics.want(listOf(song(1)))
        until { lyrics.known("library://track/1") != null }
        assertThat(lyrics.known("library://track/1")!!.lyrics).isNull()
        lyrics.want(listOf(song(1)))
        delay(100)
        assertThat(asked).hasSize(1)
        clock += MaLyrics.RETRY_MS
        answers["library://track/1"] = { answer(null, lrc) }
        lyrics.want(listOf(song(1)))
        until { lyrics.known("library://track/1")?.lyrics != null }
        assertThat(asked).hasSize(2)
    }
}
