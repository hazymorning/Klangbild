package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.single
import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.data.httpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Talks to a scripted fake Home Assistant over a real local WebSocket and its REST API: the
 * handshake, a state trigger on the followed entities (or state events for a user who is no
 * administrator), states read over REST, service calls with responses, a wrong token and
 * reconnects.
 */
class HaConnectionTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = httpClient(OkHttp.create())
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val received = CopyOnWriteArrayList<JsonObject>()
    private val restPaths = CopyOnWriteArrayList<String>()
    private val upgrades = AtomicInteger()

    /** Behavior of the fake server. */
    @Volatile private var acceptToken = "good"
    @Volatile private var admin = true
    @Volatile private var closeAfterSubscribe = false
    @Volatile private var refusedRefreshes = 0
    private val tokenRequests = CopyOnWriteArrayList<String>()

    /** The states the REST API knows. */
    private val states = ConcurrentHashMap<String, String>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
        states["media_player.wz"] = state("media_player.wz", "playing", """{"media_title":"Erstes Lied","media_artist":"A"}""", "18:00:00")
        states["sun.sun"] = state("sun.sun", "above_horizon", """{"elevation":12.5}""", "17:00:00")
        states["media_player.kueche"] = state("media_player.kueche", "playing", """{"friendly_name":"Küche"}""", "18:00:00")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun state(id: String, state: String, attributes: String, time: String) =
        """{"entity_id":"$id","state":"$state","attributes":$attributes,
            "last_changed":"2026-10-10T$time+00:00","last_updated":"2026-10-10T$time+00:00",
            "context":{"id":"01K","parent_id":null,"user_id":null}}"""

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path == "/api/websocket" -> MockResponse.Builder().webSocketUpgrade(FakeHa()).build()
            path == "/auth/token" -> {
                val form = request.body?.utf8().orEmpty()
                tokenRequests += form
                if (refusedRefreshes > 0) {
                    refusedRefreshes--
                    MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build()
                } else {
                    MockResponse.Builder().body("""{"access_token":"good","expires_in":1800,"token_type":"Bearer"}""").build()
                }
            }
            path.startsWith("/api/states/") -> {
                restPaths += path
                if (request.headers["Authorization"] != "Bearer $acceptToken") {
                    MockResponse.Builder().code(401).build()
                } else {
                    states[path.removePrefix("/api/states/")]?.let { MockResponse.Builder().body(it).build() }
                        ?: MockResponse.Builder().code(404).body("""{"message":"Entity not found."}""").build()
                }
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private inner class FakeHa : WebSocketListener() {
        // As Home Assistant does, a close is answered.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            upgrades.incrementAndGet()
            sockets += webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            received += msg
            val id = msg["id"]?.jsonPrimitive?.content
            fun ok(result: String = "null") = webSocket.send("""{"id":$id,"type":"result","success":true,"result":$result}""")
            when (msg["type"]?.jsonPrimitive?.content) {
                "auth" -> if (msg["access_token"]?.jsonPrimitive?.content == acceptToken) {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                } else {
                    webSocket.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
                    webSocket.close(1000, null)
                }
                "subscribe_trigger" -> if (admin) {
                    ok()
                    if (closeAfterSubscribe) webSocket.close(1001, "restart")
                } else {
                    webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized","message":"Unauthorized"}}""")
                }
                "subscribe_events" -> ok()
                "unsubscribe_events" -> ok()
                "get_config" -> ok("""{"latitude":52.52,"longitude":13.4,"time_zone":"Europe/Berlin"}""")
                "config/entity_registry/list_for_display" -> ok(
                    """{"entity_categories":{},"entities":[{"ei":"button.wz_favorite","pl":"music_assistant","di":"d1","tk":"favorite_now_playing"},{"ei":"light.x"}]}""",
                )
                // A light's call is answered spaced, as JSON allows, and names no followed entity.
                "call_service" -> if (msg["domain"]?.jsonPrimitive?.content == "light") {
                    webSocket.send("""{"id": $id, "type": "result", "success": true, "result": {"context": {"id": "c"}}}""")
                } else {
                    ok("""{"context":{"id":"c"},"response":{"media_player.wz":{"next_item":{"name":"Zweites Lied"}}}}""")
                }
                "ping" -> webSocket.send("""{"id":$id,"type":"pong"}""")
            }
        }
    }

    private fun ofType(t: String) = received.filter { it["type"]?.jsonPrimitive?.content == t }

    /** A change as a state trigger reports it. */
    private fun triggerEvent(subscription: String, id: String, newState: String) =
        """{"id":$subscription,"type":"event","event":{"variables":{"trigger":{"id":"0","idx":"0","platform":"state",
            "entity_id":"$id","from_state":null,"to_state":$newState,"for":null,"attribute":null,"description":"state of $id"}},
            "context":null}}"""

    private fun connection() = HaConnection(
        http, scope,
        backoffFactory = { Backoff(baseMs = 20, maxMs = 100, random = Random(3)) },
        restRetryMs = listOf(20L, 40L),
    )

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun auth(token: String = "good") = HaAuth(http, baseUrl(), HaCredentials.LongLived(token))

    @Test
    fun `follows entities with a state trigger, reads them over REST and calls services`() = runBlocking {
        val conn = connection()
        conn.start(auth(), listOf("media_player.wz", "sun.sun"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.Connected } }
        val first = withTimeout(5_000) { conn.entities.first { it.keys.containsAll(listOf("media_player.wz", "sun.sun")) } }
        assertThat(first.getValue("media_player.wz").string("media_title")).isEqualTo("Erstes Lied")
        assertThat(first.getValue("sun.sun").number("elevation")).isEqualTo(12.5)

        val sub = ofType("subscribe_trigger").single()
        val trigger = sub["trigger"]!!.jsonObject
        assertThat(trigger["platform"]).isEqualTo(JsonPrimitive("state"))
        assertThat(trigger["entity_id"].toString()).isEqualTo("""["media_player.wz","sun.sun"]""")
        assertThat(restPaths).containsAtLeast("/api/states/media_player.wz", "/api/states/sun.sun")

        // A change arrives as the trigger's new state, attributes and all.
        val subId = sub["id"]!!.jsonPrimitive.content
        sockets.last().send(triggerEvent(subId, "media_player.wz", state("media_player.wz", "paused", """{"media_title":"Erstes Lied"}""", "18:01:00")))
        val changed = withTimeout(5_000) { conn.entities.first { it["media_player.wz"]?.state == "paused" } }
        assertThat(changed.getValue("media_player.wz").string("media_title")).isEqualTo("Erstes Lied")

        val response = conn.callService("music_assistant", "get_queue", "media_player.wz", returnResponse = true)
        assertThat(response!!["media_player.wz"]!!.jsonObject["next_item"]!!.jsonObject["name"])
            .isEqualTo(JsonPrimitive("Zweites Lied"))
        val call = ofType("call_service").single()
        assertThat(call["return_response"]).isEqualTo(JsonPrimitive(true))
        assertThat(call["target"].toString()).isEqualTo("""{"entity_id":"media_player.wz"}""")
        assertThat(conn.callServiceForContext("media_player", "media_pause", "media_player.wz")).isEqualTo("c")

        // Where Home Assistant stands and its entity registry, asked once on the side.
        assertThat(withTimeout(5_000) { conn.config.first { it != null } }!!["time_zone"]).isEqualTo(JsonPrimitive("Europe/Berlin"))
        val registry = withTimeout(5_000) { conn.registry.first { it != null } }!!
        assertThat(registry).containsExactlyInAnyOrder(RegistryEntry("button.wz_favorite", "music_assistant", "d1", "favorite_now_playing"))
        conn.stop()
    }

    @Test
    fun `a user who is no administrator follows state events instead`() = runBlocking {
        admin = false
        val conn = connection()
        conn.start(auth(), listOf("media_player.wz"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.Connected } }
        withTimeout(5_000) { conn.entities.first { it.containsKey("media_player.wz") } }
        val sub = ofType("subscribe_events").single()
        assertThat(sub["event_type"]).isEqualTo(JsonPrimitive("state_changed"))

        val subId = sub["id"]!!.jsonPrimitive.content
        fun event(id: String, state: String) =
            """{"id":$subId,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"$id",
                "old_state":null,"new_state":$state},"origin":"LOCAL","time_fired":"2026-10-10T18:02:00+00:00"}}"""
        // Everything in the house arrives; only the followed entities are kept.
        sockets.last().send(event("light.flur", state("light.flur", "on", "{}", "18:02:00")))
        sockets.last().send(event("media_player.wz", state("media_player.wz", "paused", "{}", "18:02:00")))
        val now = withTimeout(5_000) { conn.entities.first { it["media_player.wz"]?.state == "paused" } }
        assertThat(now.keys).containsExactlyInAnyOrder("media_player.wz")
        // Removed from Home Assistant: gone here too.
        sockets.last().send(event("media_player.wz", "null"))
        withTimeout(5_000) { conn.entities.first { it.isEmpty() } }
        // A call's result comes through, though it names none of them.
        assertThat(conn.callServiceForContext("light", "turn_on", "light.flur")).isEqualTo("c")
        conn.stop()
    }

    @Test
    fun `follows the other speakers of a group in a second trigger`() = runBlocking {
        val conn = connection()
        conn.start(auth(), listOf("media_player.wz"))
        withTimeout(5_000) { conn.entities.first { it.containsKey("media_player.wz") } }

        conn.followAlso(setOf("media_player.kueche"))
        val both = withTimeout(5_000) { conn.entities.first { it.containsKey("media_player.kueche") } }
        assertThat(both.getValue("media_player.kueche").string("friendly_name")).isEqualTo("Küche")
        assertThat(both.keys).contains("media_player.wz")
        val subs = ofType("subscribe_trigger")
        assertThat(subs).hasSize(2)
        assertThat(subs[1]["trigger"]!!.jsonObject["entity_id"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactlyInAnyOrder("media_player.kueche")

        // A change of the extra entity arrives like any other.
        val extraId = subs[1]["id"]!!.jsonPrimitive.content
        sockets.last().send(triggerEvent(extraId, "media_player.kueche", state("media_player.kueche", "paused", "{}", "18:03:00")))
        withTimeout(5_000) { conn.entities.first { it["media_player.kueche"]?.state == "paused" } }

        // The group breaks up: the second subscription ends, its entity leaves.
        conn.followAlso(emptySet())
        val alone = withTimeout(5_000) { conn.entities.first { !it.containsKey("media_player.kueche") } }
        assertThat(alone.keys).contains("media_player.wz")
        withTimeout(5_000) { while (ofType("unsubscribe_events").isEmpty()) delay(20) }
        assertThat(ofType("unsubscribe_events").single()["subscription"]!!.jsonPrimitive.content).isEqualTo(extraId)
        conn.stop()
    }

    @Test
    fun `an entity Home Assistant does not have is reported missing`() = runBlocking {
        val conn = connection()
        conn.start(auth(), listOf("media_player.gone", "sun.sun"))
        val missing = withTimeout(5_000) { conn.missing.first { it.isNotEmpty() } }
        assertThat(missing).containsExactlyInAnyOrder("media_player.gone")
        assertThat(conn.entities.value.keys).doesNotContain("media_player.gone")
        conn.stop()
    }

    @Test
    fun `a refused token is never sent again, not even on request`() = runBlocking {
        val conn = connection()
        conn.start(auth(token = "wrong"), listOf("media_player.wz"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.AuthFailed } }
        // Each attempt counts as a failed login in Home Assistant.
        conn.retryNow()
        delay(400)
        assertThat(upgrades.get()).isEqualTo(1)
        conn.stop()
    }

    @Test
    fun `a signed-in connection gets its access token with the refresh token`() = runBlocking {
        val conn = connection()
        val auth = HaAuth(http, baseUrl(), HaCredentials.SignedIn("refresh-1", "http://192.168.1.23:8765/"))
        conn.start(auth, listOf("media_player.wz"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.Connected } }
        assertThat(tokenRequests.single()).isEqualTo(
            "grant_type=refresh_token&refresh_token=refresh-1&client_id=http%3A%2F%2F192.168.1.23%3A8765%2F",
        )
        conn.stop()
    }

    @Test
    fun `a refused refresh token is never tried again`() = runBlocking {
        refusedRefreshes = 1
        val conn = connection()
        val auth = HaAuth(http, baseUrl(), HaCredentials.SignedIn("revoked", "http://192.168.1.23:8765/"))
        conn.start(auth, listOf("media_player.wz"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.AuthFailed } }
        // Each refused refresh counts as a failed login in Home Assistant: no second one, not even on request.
        conn.retryNow()
        delay(300)
        assertThat(tokenRequests).hasSize(1)
        assertThat(upgrades.get()).isEqualTo(0)
        conn.stop()
    }

    @Test
    fun `reconnects with backoff after the server goes away`() = runBlocking {
        closeAfterSubscribe = true
        val conn = connection()
        conn.start(auth(), listOf("media_player.wz"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.Waiting } }
        // Home Assistant is back.
        closeAfterSubscribe = false
        withTimeout(5_000) {
            conn.status.first { it == HaStatus.Connected && upgrades.get() >= 2 }
        }
        conn.stop()
    }

    @Test
    fun `a wake-up skips only a wait that is under way`() = runBlocking {
        val conn = HaConnection(http, scope, backoffFactory = { Backoff(baseMs = 10_000, maxMs = 10_000, random = Random(3)) })
        conn.start(auth(), listOf("media_player.wz"))
        withTimeout(5_000) { conn.status.first { it == HaStatus.Connected } }
        // The network is reported back while the connection stands, then the connection is lost.
        conn.retryNow()
        sockets.single().close(1001, "restart")
        withTimeout(5_000) { conn.status.first { it == HaStatus.Waiting } }
        delay(300)
        assertThat(upgrades.get()).isEqualTo(1)
        // Now the network comes back while it waits.
        conn.retryNow()
        withTimeout(5_000) { conn.status.first { it == HaStatus.Connected && upgrades.get() == 2 } }
        conn.stop()
    }

    @Test
    fun `an unreachable server is tried again and again`() = runBlocking {
        val port = server.port
        server.close()
        val conn = connection()
        conn.start(HaAuth(http, "http://127.0.0.1:$port", HaCredentials.LongLived("good")), listOf("media_player.wz"))
        // A wait comes after every attempt.
        withTimeout(5_000) { conn.status.filter { it == HaStatus.Waiting }.take(3).collect() }
        conn.stop()
    }
}
