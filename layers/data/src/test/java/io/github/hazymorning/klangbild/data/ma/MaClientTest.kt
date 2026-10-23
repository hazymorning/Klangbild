package io.github.hazymorning.klangbild.data.ma

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.domain.MaLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test

/** Music Assistant's HTTP API against a stand-in server. */
class MaClientTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val client = MaClient(OkHttpClient(), base, "ma-token")

    @After
    fun stop() = server.close()

    @Test
    fun `a command is one authorized post to the api`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""[{"name":"x"}]""").build())
        val items = client.items("q1", offset = 4, limit = 9)
        assertThat(items).hasSize(1)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer ma-token")
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertThat(body["command"]!!.jsonPrimitive.content).isEqualTo("player_queues/items")
        val args = body["args"]!!.jsonObject
        assertThat(args["queue_id"]!!.jsonPrimitive.content).isEqualTo("q1")
        assertThat(args["offset"]!!.jsonPrimitive.int).isEqualTo(4)
        assertThat(args["limit"]!!.jsonPrimitive.int).isEqualTo(9)
    }

    @Test
    fun `the check tells a rejected token from a server that is not there`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("[]").build())
        assertThat(client.check()).isEqualTo(MaLink.CONNECTED)
        server.enqueue(MockResponse.Builder().code(401).build())
        assertThat(client.check()).isEqualTo(MaLink.AUTH_FAILED)
        server.enqueue(MockResponse.Builder().body("<html>not music assistant</html>").build())
        assertThat(client.check()).isEqualTo(MaLink.UNREACHABLE)
        server.enqueue(MockResponse.Builder().code(500).build())
        assertThat(client.check()).isEqualTo(MaLink.UNREACHABLE)
        server.close()
        assertThat(client.check()).isEqualTo(MaLink.UNREACHABLE)
    }

    @Test
    fun `a party is asked where it plays and for the link its guests open`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("\"ma_kitchen\"").build())
        assertThat(client.partyPlayer()).isEqualTo("ma_kitchen")
        server.enqueue(MockResponse.Builder().body("\"http://192.168.1.5:8095/?join=Q4WZ8TNP\"").build())
        assertThat(client.partyUrl()).isEqualTo("http://192.168.1.5:8095/?join=Q4WZ8TNP")
        // Guest access off: no party, no link.
        server.enqueue(MockResponse.Builder().body("null").build())
        assertThat(client.partyPlayer()).isNull()
        server.enqueue(MockResponse.Builder().body("null").build())
        assertThat(client.partyUrl()).isNull()
        val commands = (1..4).map { Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content }
        assertThat(commands).containsExactly("party/player", "party/url", "party/player", "party/url").inOrder()
    }

    @Test
    fun `a server without the party plugin says it does not know the question`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("Invalid Command: party/player").build())
        val unknown = runCatching { client.partyPlayer() }.exceptionOrNull()
        assertThat(unknown).isInstanceOf(MaUnknownCommandException::class.java)
    }

    @Test
    fun `the address is read from a picture link`() {
        assertThat(MaClient.addressIn("http://192.168.1.5:8095/imageproxy/3f2a?size=512")).isEqualTo("http://192.168.1.5:8095")
        assertThat(MaClient.addressIn("http://mass.local:8095/imageproxy?path=x&size=0")).isEqualTo("http://mass.local:8095")
        assertThat(MaClient.addressIn("https://i.scdn.co/image/ab67616d0000b273abc")).isNull()
        assertThat(MaClient.addressIn(null)).isNull()
    }

    @Test
    fun `the player around this TV's speaker is found by its output, or is the speaker itself`() = runBlocking {
        val players = """[{"player_id":"media_player_kitchen","output_protocols":[]},
            {"player_id":"up1a2b3c4d","type":"player","output_protocols":[{"output_protocol_id":"other"},{"output_protocol_id":"Q2xpZW50SWQ"}]}]"""
        server.enqueue(MockResponse.Builder().body(players).build())
        assertThat(client.playerOf("Q2xpZW50SWQ")).isEqualTo("up1a2b3c4d")
        assertThat(Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content).isEqualTo("players/all")
        // Not named yet (the first seconds after the speaker first connects).
        server.enqueue(MockResponse.Builder().body("""[{"player_id":"media_player_kitchen"}]""").build())
        assertThat(client.playerOf("Q2xpZW50SWQ")).isNull()
        server.enqueue(MockResponse.Builder().body("""[{"player_id":"Q2xpZW50SWQ"}]""").build())
        assertThat(client.playerOf("Q2xpZW50SWQ")).isEqualTo("Q2xpZW50SWQ")
    }

    @Test
    fun `its clock is asked with the time command`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("1792630000.125").build())
        assertThat(client.serverTime()).isEqualTo(1_792_630_000.125)
        assertThat(Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content).isEqualTo("time")
    }
}

