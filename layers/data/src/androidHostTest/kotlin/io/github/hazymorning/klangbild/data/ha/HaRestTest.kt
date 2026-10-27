package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.github.hazymorning.klangbild.data.httpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test

/** One entity's state over Home Assistant's REST API, against a stand-in server. */
class HaRestTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val http = httpClient(OkHttp.create())

    private val sun = """{"entity_id":"sun.sun","state":"above_horizon","attributes":{},
        "last_changed":"2026-10-10T18:00:00+00:00","last_updated":"2026-10-10T18:00:00+00:00"}"""

    /** The access token the stand-in server takes. */
    @Volatile private var accepted = "token-2"

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `an answer that isn't a state counts as no answer`() = runBlocking<Unit> {
        val rest = HaRest(http, HaAuth(http, base, HaCredentials.LongLived("t")))
        // A proxy in front of Home Assistant may answer with a page of its own.
        server.enqueue(MockResponse.Builder().body("<html>Bad gateway</html>").build())
        assertThat(rest.state("sun.sun")).isEqualTo(HaRest.Fetched.Failed)
        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Entity not found."}""").build())
        assertThat(rest.state("sun.sun")).isEqualTo(HaRest.Fetched.Missing)
        server.enqueue(MockResponse.Builder().body(sun).build())
        assertThat(rest.state("sun.sun")).isInstanceOf<HaRest.Fetched.Found>()
    }

    @Test
    fun `a refused access token from a sign-in is replaced once, not again`() = runBlocking {
        val issued = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/auth/token" ->
                    MockResponse.Builder().body("""{"access_token":"token-${issued.incrementAndGet()}","expires_in":1800}""").build()
                request.headers["Authorization"] == "Bearer $accepted" -> MockResponse.Builder().body(sun).build()
                else -> MockResponse.Builder().code(401).build()
            }
        }
        val rest = HaRest(http, HaAuth(http, base, HaCredentials.SignedIn("refresh", "http://192.168.1.23:8765/")))
        assertThat(rest.state("sun.sun")).isInstanceOf<HaRest.Fetched.Found>()
        assertThat(issued.get()).isEqualTo(2)
        accepted = "none"
        assertThat(rest.state("sun.sun")).isEqualTo(HaRest.Fetched.Failed)
        assertThat(issued.get()).isEqualTo(3)
    }
}
