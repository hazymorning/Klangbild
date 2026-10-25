package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.doesNotContainKey
import assertk.assertions.endsWith
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The source against a stand-in Home Assistant (and Music Assistant): what it asks, and how
 * often. Quick skips, players without a queue, jumps, a book's chapters and speed.
 */
class HaSourceTest {
    private lateinit var ha: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = httpClient(OkHttp.create())
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val calls = CopyOnWriteArrayList<JsonObject>()
    private val maCommands = CopyOnWriteArrayList<String>()

    /** The player's attributes as Home Assistant first reports them, and how it answers `get_queue`. */
    private var player = """{"media_title":"Erstes Lied","media_artist":"A","app_id":"music_assistant","mass_player_type":"player"}"""
    private var queueAnswer: (() -> String)? = { """{"media_player.wz":{"queue_id":"q1","current_index":0,"current_item":{"name":"Erstes Lied","media_item":{"media_type":"track","name":"Erstes Lied"}},"next_item":null}}""" }
    private var subscription: String? = null
    private var changes = 0

    /** Home Assistant takes `get_queue` and never answers. */
    private var queueSilent = false

    /** The entity registry as listed for display. */
    private var registry = """[{"ei":"media_player.wz","pl":"music_assistant","di":"d1"}]"""

    /** How many reads of the registry Home Assistant answers with an error before it answers. */
    @Volatile private var registryErrors = 0

    /** This TV's own speaker in Home Assistant: its state and attributes, and the subscriptions that follow entities. */
    private var tvState = "idle"
    private var tv = """{"friendly_name":"Fernseher","supported_features":17413}"""
    private val triggers = CopyOnWriteArrayList<Pair<String, String>>()

    @BeforeTest
    fun setUp() {
        ha = MockWebServer()
        ha.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/websocket" -> MockResponse.Builder().webSocketUpgrade(FakeHa()).build()
                    path == "/api/states/media_player.wz" -> MockResponse.Builder().body(playerState(Json.parseToJsonElement(player).jsonObject)).build()
                    path == "/api/states/media_player.tv" -> MockResponse.Builder().body(tvJson()).build()
                    path == "/api/states/input_boolean.party" -> MockResponse.Builder().body(
                        """{"entity_id":"input_boolean.party","state":"on","attributes":{},"last_changed":"2026-10-10T18:00:00+00:00",
                            "last_updated":"2026-10-10T18:00:00+00:00","context":{"id":"y","parent_id":null,"user_id":null}}""",
                    ).build()
                    path == "/api/states/sensor.outside" -> MockResponse.Builder().body(
                        """{"entity_id":"sensor.outside","state":"12.345","attributes":{"unit_of_measurement":"°C"},"last_changed":"2026-10-10T18:00:00+00:00",
                            "last_updated":"2026-10-10T18:00:00+00:00","context":{"id":"z","parent_id":null,"user_id":null}}""",
                    ).build()
                    else -> MockResponse.Builder().code(404).body("""{"message":"Entity not found."}""").build()
                }
            }
        }
        ha.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        ha.close()
    }

    /** What Home Assistant says the player does. */
    private var playerStatus = "playing"

    /** When Home Assistant says the player's state changed (a moment in the past by default). */
    private var stamp: () -> String = { "2026-10-10T18:00:%02d+00:00".format(changes++ % 60) }

    @Synchronized
    private fun playerState(attributes: JsonObject): String {
        val time = stamp()
        return """{"entity_id":"media_player.wz","state":"$playerStatus","attributes":$attributes,
            "last_changed":"$time","last_updated":"$time","context":{"id":"x","parent_id":null,"user_id":null}}"""
    }

    private inner class FakeHa : WebSocketListener() {
        // As Home Assistant does, a close is answered.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            val id = msg["id"]?.jsonPrimitive?.content
            fun ok(result: String = "null") = webSocket.send("""{"id":$id,"type":"result","success":true,"result":$result}""")
            when (msg["type"]?.jsonPrimitive?.content) {
                "auth" -> webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                "subscribe_trigger" -> {
                    if (subscription == null) subscription = id
                    triggers += id!! to msg["trigger"]!!.jsonObject["entity_id"].toString()
                    ok()
                }
                "config/entity_registry/get_entries" ->
                    ok("""{"media_player.wz":{"entity_id":"media_player.wz","unique_id":"mass_wz"},"media_player.tv":{"entity_id":"media_player.tv","unique_id":"up1a2b3c4d"}}""")
                "config/entity_registry/list_for_display" -> if (registryErrors > 0) {
                    registryErrors--
                    webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_error","message":"busy"}}""")
                } else {
                    ok("""{"entity_categories":{},"entities":$registry}""")
                }
                "get_config" -> ok("""{"latitude":52.52,"longitude":13.4,"unit_system":{"temperature":"°C"}}""")
                "unsubscribe_events" -> ok()
                "call_service" -> {
                    calls += msg
                    val target = (msg["target"] as? JsonObject)?.get("entity_id")?.jsonPrimitive?.content
                    // A long automation: Home Assistant answers once its actions are done.
                    if (target == "automation.slow") return
                    if (queueSilent && msg["service"]?.jsonPrimitive?.content == "get_queue") return
                    val answer = when {
                        target == "script.broken" -> null
                        msg["service"]?.jsonPrimitive?.content == "get_queue" -> queueAnswer?.invoke()
                        else -> "null"
                    }
                    if (answer == null) {
                        webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"home_assistant_error","message":"no queue"}}""")
                    } else {
                        ok("""{"context":{"id":"c"},"response":$answer}""")
                    }
                }
                "ping" -> webSocket.send("""{"id":$id,"type":"pong"}""")
            }
        }
    }

    /** Home Assistant reports a new title. */
    private fun play(title: String) = report("media_title" to title)

    /** Home Assistant reports the player with these attributes changed. */
    private fun report(vararg changes: Pair<String, String>) {
        val attributes = JsonObject(Json.parseToJsonElement(player).jsonObject + changes.map { (k, v) -> k to JsonPrimitive(v) })
        sockets.last().send(
            """{"id":$subscription,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"media_player.wz",
                "from_state":null,"to_state":${playerState(attributes)}}},"context":null}}""",
        )
    }

    private fun queueCalls() = calls.count { it["service"]?.jsonPrimitive?.content == "get_queue" }

    private fun tvJson(): String {
        val time = "2026-10-10T18:01:%02d+00:00".format(changes++ % 60)
        return """{"entity_id":"media_player.tv","state":"$tvState","attributes":$tv,
            "last_changed":"$time","last_updated":"$time","context":{"id":"t","parent_id":null,"user_id":null}}"""
    }

    /** Home Assistant reports this TV's own speaker anew, on the subscription that follows it. */
    private fun reportTv(state: String, title: String?) {
        tvState = state
        tv = if (title == null) {
            """{"friendly_name":"Fernseher","supported_features":17413}"""
        } else {
            """{"friendly_name":"Fernseher","supported_features":17413,"media_title":"$title","media_artist":"B"}"""
        }
        val id = triggers.last { "media_player.tv" in it.second }.first
        sockets.last().send(
            """{"id":$id,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"media_player.tv",
                "from_state":null,"to_state":${tvJson()}}},"context":null}}""",
        )
    }

    /** A stand-in Music Assistant that names the player around this TV's speaker (`Q2xpZW50SWQ`). */
    private fun speakerServer() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                return MockResponse.Builder().body(
                    when (command) {
                        "players/all" -> """[{"player_id":"up1a2b3c4d","output_protocols":[{"output_protocol_id":"Q2xpZW50SWQ"}]}]"""
                        "party/player" -> "null"
                        else -> "[]"
                    },
                ).build()
            }
        }
        start()
    }

    private fun source(
        ma: MaClient? = null,
        entity: String = "media_player.wz",
        speaker: kotlinx.coroutines.flow.StateFlow<SpeakerStatus>? = null,
        refreshMs: Long = 60_000L,
        partyMs: Long = HaSource.PARTY_MS,
        detailsWaitMs: Long = QueueReader.DETAILS_WAIT_MS,
        queueSettleMs: Long = QueueReader.QUEUE_SETTLE_MS,
        maQueueMs: Long = QueueReader.MA_QUEUE_MS,
    ) = HaSource(
        http,
        scope,
        HaConfig(HaAuth(http, ha.url("/").toString().trimEnd('/'), HaCredentials.LongLived("good"))),
        entity,
        ma = ma,
        queueRefreshMs = refreshMs,
        partyMs = partyMs,
        actionAnswerMs = 500L,
        detailsWaitMs = detailsWaitMs,
        queueSettleMs = queueSettleMs,
        maQueueMs = maQueueMs,
        speaker = speaker,
        ownRetryMs = 50L,
        ownBackMs = 300L,
    )

    @Test
    fun `quick skips ask for the queue once, for where they end`() = runBlocking {
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player?.track?.title == "Erstes Lied" } }
        val before = queueCalls()
        for (i in 2..6) {
            play("Lied $i")
            delay(60)
        }
        withTimeout(5_000) { s.state.first { it.player?.track?.title == "Lied 6" } }
        delay(1_000)
        assertThat(queueCalls() - before).isEqualTo(1)
        s.stop()
    }

    @Test
    fun `a player that is not Music Assistant's is never asked for a queue`() = runBlocking {
        player = """{"media_title":"Erstes Lied","media_artist":"A"}"""
        val s = source(refreshMs = 100)
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        delay(800)
        assertThat(queueCalls()).isEqualTo(0)
        s.stop()
    }

    @Test
    fun `a player without a queue is not asked again while the track stays`() = runBlocking {
        queueAnswer = null
        val s = source(refreshMs = 100)
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        delay(1_000)
        assertThat(queueCalls()).isEqualTo(1)
        s.stop()
    }

    @Test
    fun `a jump goes to Home Assistant in seconds`() = runBlocking {
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        s.seek(61_700)
        withTimeout(5_000) { while (calls.none { it["service"]?.jsonPrimitive?.content == "media_seek" }) delay(20) }
        val seek = calls.first { it["service"]?.jsonPrimitive?.content == "media_seek" }
        assertThat(seek["service_data"].toString()).isEqualTo("""{"seek_position":61.7}""")
        assertThat(seek["target"].toString()).isEqualTo("""{"entity_id":"media_player.wz"}""")
        s.stop()
    }

    @Test
    fun `only what the player supports is sent`() = runBlocking {
        // Play, pause and next only (supported_features 1 + 32 + 16384): no seek, no shuffle, no volume.
        player = """{"media_title":"Erstes Lied","media_artist":"A","supported_features":16417}"""
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        s.seek(10_000)
        assertThat(s.shuffle(true)).isFalse()
        s.send(io.github.hazymorning.klangbild.data.RemoteCommand.VOLUME_UP)
        s.send(io.github.hazymorning.klangbild.data.RemoteCommand.PREVIOUS)
        s.send(io.github.hazymorning.klangbild.data.RemoteCommand.PLAY_PAUSE)
        s.send(io.github.hazymorning.klangbild.data.RemoteCommand.NEXT)
        withTimeout(5_000) { while (calls.size < 2) delay(20) }
        delay(200)
        assertThat(calls.map { it["service"]!!.jsonPrimitive.content }).containsExactly("media_play_pause", "media_next_track")
        s.stop()
    }

    /** A stand-in Music Assistant whose Party plugin answers `party/player` with [party]. */
    private fun partyServer(party: () -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                return if (command == "party/player") party() else MockResponse.Builder().body("[]").build()
            }
        }
        start()
    }

    private fun partyQuestions() = maCommands.count { it == "party/player" }

    @Test
    fun `a party is told while it plays to this speaker, not while it plays elsewhere`() = runBlocking {
        player = """{"media_title":"Erstes Lied","media_artist":"A","app_id":"music_assistant","mass_player_type":"player","active_queue":"q1"}"""
        var answer = "\"q2\""
        val ma = partyServer { MockResponse.Builder().body(answer).build() }
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), partyMs = 100L)
            s.start()
            withTimeout(5_000) { s.state.first { it.player != null } }
            withTimeout(5_000) { while (partyQuestions() < 3) delay(20) }
            assertThat(s.state.value.party).isFalse()
            answer = "\"q1\""
            withTimeout(5_000) { s.state.first { it.party } }
            // Guest access off: the party is over.
            answer = "null"
            withTimeout(5_000) { s.state.first { !it.party } }
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `a party outlasts one failed question, not two in a row`() = runBlocking {
        player = """{"media_title":"Erstes Lied","media_artist":"A","app_id":"music_assistant","mass_player_type":"player","active_queue":"q1"}"""
        val failing = AtomicBoolean(false)
        val failures = AtomicInteger(0)
        val secondAsked = CountDownLatch(1)
        val answerIt = CountDownLatch(1)
        val ma = partyServer {
            if (!failing.get()) {
                MockResponse.Builder().body("\"q1\"").build()
            } else {
                if (failures.incrementAndGet() == 2) {
                    secondAsked.countDown()
                    answerIt.await(5, TimeUnit.SECONDS)
                }
                MockResponse.Builder().code(500).build()
            }
        }
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), partyMs = 100L)
            s.start()
            withTimeout(5_000) { s.state.first { it.party } }
            failing.set(true)
            // The second question is under way, so the first failure has been taken in.
            assertThat(withContext(Dispatchers.IO) { secondAsked.await(5, TimeUnit.SECONDS) }).isTrue()
            assertThat(s.state.value.party).isTrue()
            answerIt.countDown()
            withTimeout(5_000) { s.state.first { !it.party } }
            s.stop()
        } finally {
            answerIt.countDown()
            ma.close()
        }
    }

    @Test
    fun `a server without the party plugin is not asked again`() = runBlocking {
        val ma = partyServer { MockResponse.Builder().code(400).body("Invalid Command: party/player").build() }
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), partyMs = 50L)
            s.start()
            withTimeout(5_000) { while (partyQuestions() < 1) delay(20) }
            delay(500)
            assertThat(partyQuestions()).isEqualTo(1)
            assertThat(s.state.value.party).isFalse()
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `a book comes with its chapters and speed from Music Assistant`() = runBlocking {
        player = """{"media_title":"Der Process","media_artist":"Franz Kafka","media_content_id":"library://audiobook/12",
            "media_album_name":"Josef K. wird verhaftet.\n\nEin Roman.","media_duration":32400,
            "app_id":"music_assistant","mass_player_type":"player","active_queue":"q1"}"""
        queueAnswer = {
            """{"media_player.wz":{"queue_id":"q1","current_index":0,
               "current_item":{"name":"Der Process","duration":32400,"media_item":{"media_type":"audiobook","name":"Der Process","version":""}},
               "next_item":null}}"""
        }
        val ma = MockWebServer()
        ma.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                return when (command) {
                    "player_queues/items" -> MockResponse.Builder().body(
                        """[{"queue_id":"q1","queue_item_id":"a","name":"Der Process","duration":32400,"extra_attributes":{"playback_speed":1.25},
                            "media_item":{"media_type":"audiobook","name":"Der Process","version":"","authors":["Franz Kafka"],
                              "metadata":{"chapters":[{"position":1,"name":"Verhaftung","start":0.0,"end":1834.2},
                                                      {"position":2,"name":"Gespräch mit Frau Grubach","start":1834.2,"end":null}]}}}]""",
                    ).build()
                    else -> MockResponse.Builder().body("[]").build()
                }
            }
        }
        ma.start()
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"))
            s.start()
            val state = withTimeout(5_000) { s.state.first { it.player?.track?.chapters?.isNotEmpty() == true } }
            val p = state.player!!
            val track = p.track!!
            assertThat(track.kind).isEqualTo(MediaKind.AUDIOBOOK)
            assertThat(track.album).isNull()
            assertThat(track.artists).containsExactlyInAnyOrder("Franz Kafka")
            assertThat(track.chapters.map { it.name }).containsExactly("Verhaftung", "Gespräch mit Frau Grubach")
            assertThat(p.speed).isCloseTo(1.25f, 1e-6f)
            assertThat(maCommands).contains("player_queues/items")
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `a station keeps the song's picture from Home Assistant and names itself in the bar's place`() = runBlocking {
        player = """{"media_title":"Cold Little Heart","media_artist":"Michael Kiwanuka","media_content_id":"library://radio/7",
            "media_album_name":"Radio Paradise","entity_picture":"/api/media_player_proxy/media_player.wz?token=t&cache=song",
            "app_id":"music_assistant","mass_player_type":"player","active_queue":"q1"}"""
        queueAnswer = {
            """{"media_player.wz":{"queue_id":"q1","current_index":0,
               "current_item":{"name":"Radio Paradise","stream_title":"Michael Kiwanuka - Cold Little Heart",
                 "media_item":{"media_type":"radio","name":"Radio Paradise","image":"https://cdn.example/station.png"}},
               "next_item":{"name":"SWR2","media_item":{"media_type":"radio","name":"SWR2","image":"https://cdn.example/swr2.png"}}}}"""
        }
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.queueKnown } }
        val t = s.state.value.player!!.track!!
        assertThat(t.kind).isEqualTo(MediaKind.RADIO)
        assertThat(t.source).isEqualTo("Radio Paradise")
        assertThat(t.album).isNull()
        // The song's picture as Home Assistant has it, never the station's logo instead.
        assertThat(t.artworkUrl).isNotNull().endsWith("cache=song")
        s.stop()
    }

    @Test
    fun `a station's song picture from Music Assistant's own queue goes first`() = runBlocking {
        player = """{"media_title":"Cold Little Heart","media_artist":"Michael Kiwanuka","media_content_id":"library://radio/7",
            "media_album_name":"Radio Paradise","entity_picture":"/api/media_player_proxy/media_player.wz?token=t&cache=station",
            "app_id":"music_assistant","mass_player_type":"player","active_queue":"q1"}"""
        queueAnswer = {
            """{"media_player.wz":{"queue_id":"q1","current_index":0,
               "current_item":{"name":"Radio Paradise","stream_title":"Michael Kiwanuka - Cold Little Heart",
                 "media_item":{"media_type":"radio","name":"Radio Paradise","image":"https://cdn.example/station.png"}},
               "next_item":null}}"""
        }
        val ma = MockWebServer()
        ma.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                return when (command) {
                    "player_queues/items" -> MockResponse.Builder().body(
                        """[{"queue_id":"q1","queue_item_id":"a","name":"Radio Paradise",
                            "streamdetails":{"stream_title":"Michael Kiwanuka - Cold Little Heart",
                              "stream_metadata":{"title":"Cold Little Heart","artist":"Michael Kiwanuka","album":"Love & Hate",
                                "image_url":"https://img.example/love-and-hate.jpg"}},
                            "media_item":{"media_type":"radio","name":"Radio Paradise",
                              "image":{"path":"https://cdn.example/station.png","provider":"radiobrowser","remotely_accessible":true}}}]""",
                    ).build()
                    else -> MockResponse.Builder().body("[]").build()
                }
            }
        }
        ma.start()
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"))
            s.start()
            val t = withTimeout(5_000) { s.state.first { it.player?.track?.artworkUrl?.contains("love-and-hate") == true } }.player!!.track!!
            assertThat(t.album).isEqualTo("Love & Hate")
            assertThat(t.source).isEqualTo("Radio Paradise")
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `a station's song keeps its own picture while Home Assistant falls back to the station's`() = runBlocking {
        val stationPicture = "/api/media_player_proxy/media_player.wz?token=t&cache=station"
        player = """{"media_title":"Cold Little Heart","media_artist":"Michael Kiwanuka","media_content_id":"library://radio/7",
            "media_album_name":"Radio Paradise","entity_picture":"$stationPicture",
            "app_id":"music_assistant","mass_player_type":"player","active_queue":"q1"}"""
        queueAnswer = {
            """{"media_player.wz":{"queue_id":"q1","current_index":0,
               "current_item":{"name":"Radio Paradise","stream_title":"Michael Kiwanuka - Cold Little Heart",
                 "media_item":{"media_type":"radio","name":"Radio Paradise","image":"https://cdn.example/station.png"}},
               "next_item":null}}"""
        }
        val s = source()
        s.start()
        fun picture() = s.state.value.player!!.track!!.artworkUrl
        withTimeout(5_000) { s.state.first { it.queueKnown } }
        assertThat(picture()).isNotNull().endsWith("cache=station")
        // Music Assistant has found the song's picture.
        report("entity_picture" to "https://img.example/love-and-hate.jpg")
        withTimeout(5_000) { s.state.first { it.player?.track?.artworkUrl?.contains("love-and-hate") == true } }
        // For a while it shows the station's again (with a new token): the song's stays.
        report("entity_picture" to stationPicture.replace("token=t", "token=u"))
        delay(400)
        assertThat(picture()).isNotNull().contains("love-and-hate")
        // The next song starts with the station's picture.
        report("media_title" to "Spinning", "media_artist" to "Other", "entity_picture" to stationPicture)
        withTimeout(5_000) { s.state.first { it.player?.track?.title == "Spinning" } }
        assertThat(picture()).isNotNull().endsWith("cache=station")
        s.stop()
    }

    @Test
    fun `the quick settings' own buttons go to Home Assistant as their kind wants, a script with the speaker`() = runBlocking {
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        for (entity in listOf("script.ungroup_speakers", "scene.movie", "automation.night", "input_button.bell", "input_boolean.party")) {
            assertThat(s.run(QuickAction("x", entity))).isTrue()
        }
        val sent = calls.filter { it["domain"]?.jsonPrimitive?.content != "music_assistant" }
        assertThat(sent.map { it["domain"]!!.jsonPrimitive.content + "." + it["service"]!!.jsonPrimitive.content })
            .containsExactly("script.turn_on", "scene.turn_on", "automation.trigger", "input_button.press", "input_boolean.toggle")
        assertThat(sent[0]["target"].toString()).isEqualTo("""{"entity_id":"script.ungroup_speakers"}""")
        assertThat(sent[0]["service_data"].toString()).isEqualTo("""{"variables":{"player":"media_player.wz"}}""")
        assertThat(sent.drop(1).none { "service_data" in it }).isTrue()
        // A script's own variables go along; one that names the player itself wins.
        val vars = Json.parseToJsonElement("""{"mode":"party","player":"media_player.kitchen"}""").jsonObject
        assertThat(s.run(QuickAction("x", "script.mode", vars))).isTrue()
        assertThat(calls.last()["service_data"].toString()).isEqualTo("""{"variables":{"player":"media_player.kitchen","mode":"party"}}""")
        // Home Assistant says no: it failed. A long automation is still running: it was taken.
        assertThat(s.run(QuickAction("x", "script.broken"))).isFalse()
        assertThat(s.run(QuickAction("x", "automation.slow"))).isTrue()
        // What no button can work is never sent.
        assertThat(s.run(QuickAction("x", "lock.front_door"))).isFalse()
        assertThat(calls.none { it["domain"]?.jsonPrimitive?.content == "lock" }).isTrue()
        s.stop()
    }

    @Test
    fun `the buttons' entities are followed, and one Home Assistant does not know is missing`() = runBlocking {
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        s.followEntities(setOf("input_boolean.party", "script.gone"))
        val state = withTimeout(5_000) { s.state.first { it.entities["input_boolean.party"]?.state == "on" } }
        assertThat(state.entities).doesNotContainKey("script.gone")
        s.stop()
    }

    @Test
    fun `a value comes with Home Assistant's decimals for it and its unit for temperatures`() = runBlocking {
        registry = """[{"ei":"media_player.wz","pl":"music_assistant","di":"d1"},{"ei":"sensor.outside","pl":"template","dp":1}]"""
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        s.followEntities(setOf("sensor.outside"))
        val state = withTimeout(5_000) { s.state.first { it.entities["sensor.outside"] != null && it.precision.isNotEmpty() && it.temperatureUnit != null } }
        assertThat(state.entities.getValue("sensor.outside").state).isEqualTo("12.345")
        assertThat(state.precision).isEqualTo(mapOf("sensor.outside" to 1))
        assertThat(state.temperatureUnit).isEqualTo("°C")
        s.stop()
    }

    @Test
    fun `the favorite goes through the speaker's own button`() = runBlocking {
        // Found by its translation key on the speaker's device, whatever its entity id.
        registry = """[{"ei":"media_player.wz","pl":"music_assistant","di":"d1"},
            {"ei":"button.wz_favorite_current_song","pl":"music_assistant","di":"d1","tk":"favorite_now_playing"},
            {"ei":"button.kueche_favorite_current_song","pl":"music_assistant","di":"d2","tk":"favorite_now_playing"}]"""
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.favorites } }
        assertThat(s.favorite().isSuccess).isTrue()
        val press = calls.last { it["domain"]?.jsonPrimitive?.content == "button" }
        assertThat(press["service"]!!.jsonPrimitive.content).isEqualTo("press")
        assertThat(press["target"]!!.jsonObject["entity_id"]!!.jsonPrimitive.content).isEqualTo("button.wz_favorite_current_song")
        s.stop()
    }

    @Test
    fun `without a button and without Music Assistant there are no favorites`() = runBlocking {
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player != null } }
        delay(300)
        assertThat(s.state.value.favorites).isFalse()
        assertThat(s.favorite().isFailure).isTrue()
        s.stop()
    }

    /** Home Assistant's state of a Music Assistant player: its own picture is Music Assistant's small one. */
    private fun maPlayer(title: String, picture: String) =
        """{"media_title":"$title","media_artist":"A","media_album_name":"Platte","app_id":"music_assistant","mass_player_type":"player",
            "entity_picture":"/api/media_player_proxy/media_player.wz?token=t1&cache=$picture"}"""

    /** A queue item as Home Assistant's `get_queue` gives it: the song's own picture and the album's year. */
    private fun item(title: String, picture: String) =
        """{"name":"$title","media_item":{"media_type":"track","name":"$title","artists":[{"name":"A"}],
            "image":"https://cdn.example/$picture.jpg","album":{"name":"Platte","year":2024}}}"""

    private fun queueOf(current: String, next: String?) =
        """{"media_player.wz":{"queue_id":"q1","current_index":0,"current_item":$current,"next_item":${next ?: "null"}}}"""

    @Test
    fun `a Music Assistant song comes once, with its own picture and the album's year`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), null) }
        val seen = CopyOnWriteArrayList<io.github.hazymorning.klangbild.domain.TrackInfo>()
        val s = source()
        val watch = scope.launch { s.state.collect { st -> st.player?.track?.let { seen += it } } }
        s.start()
        withTimeout(5_000) { s.state.first { it.player?.track?.year == 2024 } }
        delay(300)
        // Never first with Home Assistant's small picture or without the year.
        assertThat(seen.map { it.artworkUrl }.distinct()).containsExactlyInAnyOrder("https://cdn.example/erstes.jpg")
        assertThat(seen.map { it.year }.distinct()).containsExactlyInAnyOrder(2024)
        watch.cancel()
        s.stop()
    }

    @Test
    fun `the song the queue announced as next comes at once, complete`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Zweites Lied", "zweites")) }
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player?.track?.year == 2024 } }
        val before = queueCalls()
        queueAnswer = { queueOf(item("Zweites Lied", "zweites"), null) }
        report("media_title" to "Zweites Lied", "entity_picture" to "/api/media_player_proxy/media_player.wz?token=t1&cache=c2")
        val second = withTimeout(5_000) { s.state.first { it.player?.track?.title == "Zweites Lied" } }.player!!.track!!
        // Before its own read: what the queue announced is enough.
        assertThat(queueCalls()).isEqualTo(before)
        assertThat(second.artworkUrl).isEqualTo("https://cdn.example/zweites.jpg")
        assertThat(second.year).isEqualTo(2024)
        s.stop()
    }

    @Test
    fun `a queue replaced as its first song starts names the new next song a moment later`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Zweites Lied", "zweites")) }
        val s = source(queueSettleMs = 300L)
        s.start()
        withTimeout(5_000) { s.state.first { it.next?.title == "Zweites Lied" } }
        // Music Assistant still names the old queue's next until the player reports.
        queueAnswer = { queueOf(item("Anderes Lied", "anderes"), item("Zweites Lied", "zweites")) }
        play("Anderes Lied")
        withTimeout(5_000) { s.state.first { it.player?.track?.title == "Anderes Lied" && it.queueKnown } }
        queueAnswer = { queueOf(item("Anderes Lied", "anderes"), item("Noch ein Lied", "noch")) }
        withTimeout(5_000) { s.state.first { it.next?.title == "Noch ein Lied" } }
        s.stop()
    }

    /** A song of Music Assistant's own queue (`player_queues/items`, `player_queues/get`), its id after its title. */
    private fun maItem(title: String) =
        """{"queue_id":"q1","queue_item_id":"${title.lowercase().replace(' ', '-')}","name":"$title","duration":200,
            "media_item":{"media_type":"track","name":"$title","artists":[{"name":"A"}]}}"""

    /** Music Assistant's own queue as it stands now (`player_queues/get`): the first of [titles] plays. */
    private fun maQueue(titles: List<String>) =
        """{"queue_id":"q1","current_index":0,"items":${titles.size},"current_item":${maItem(titles[0])},
            "next_item":${titles.getOrNull(1)?.let(::maItem) ?: "null"}}"""

    @Test
    fun `a queue changed while the song plays names its new next song from Music Assistant itself`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        // Home Assistant tells what came next when the song started, and hears of no change after.
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Zweites Lied", "zweites")) }
        var titles = listOf("Erstes Lied", "Zweites Lied")
        val ma = MockWebServer()
        ma.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                return when (command) {
                    "player_queues/items" -> MockResponse.Builder().body(titles.joinToString(",", "[", "]") { maItem(it) }).build()
                    "player_queues/get" -> MockResponse.Builder().body(maQueue(titles)).build()
                    else -> MockResponse.Builder().body("[]").build()
                }
            }
        }
        ma.start()
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), maQueueMs = 300L)
            s.start()
            withTimeout(5_000) { s.state.first { it.next?.title == "Zweites Lied" } }
            withTimeout(5_000) { while ("player_queues/get" !in maCommands) delay(20) }
            delay(400)
            // The same song next: Home Assistant's description stays, the picture it brings when it starts.
            assertThat(s.state.value.next!!.artworkUrl).isEqualTo("https://cdn.example/zweites.jpg")
            // A song added to play next: Music Assistant says so, and the cards to come follow.
            titles = listOf("Erstes Lied", "Eingeschoben", "Zweites Lied")
            val changed = withTimeout(5_000) { s.state.first { it.next?.title == "Eingeschoben" } }
            assertThat(changed.coming.map { it.title }).containsExactly("Eingeschoben", "Zweites Lied")
            // A song moved further down: the same song next and as many to come, yet the order follows.
            titles = listOf("Erstes Lied", "Eingeschoben", "Zweites Lied", "Drittes Lied")
            withTimeout(5_000) { s.state.first { it.coming.size == 3 } }
            titles = listOf("Erstes Lied", "Eingeschoben", "Drittes Lied", "Zweites Lied")
            val moved = withTimeout(5_000) { s.state.first { it.coming.map { c -> c.title } == listOf("Eingeschoben", "Drittes Lied", "Zweites Lied") } }
            assertThat(moved.next?.title).isEqualTo("Eingeschoben")
            // Nothing left to come: nothing is next.
            titles = listOf("Erstes Lied")
            withTimeout(5_000) { s.state.first { it.queueKnown && it.next == null } }
            s.stop()
        } finally {
            ma.close()
        }
    }

    /** Music Assistant itself, answering with its queue of [titles] while [answering]. */
    private fun maServer(titles: () -> List<String>, answering: () -> Boolean = { true }) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                if (!answering()) return MockResponse.Builder().code(500).body("{}").build()
                return when (command) {
                    "player_queues/items" -> MockResponse.Builder().body(titles().joinToString(",", "[", "]") { maItem(it) }).build()
                    "player_queues/get" -> MockResponse.Builder().body(maQueue(titles())).build()
                    else -> MockResponse.Builder().body("[]").build()
                }
            }
        }
        start()
    }

    @Test
    fun `what Music Assistant itself said comes next is forgotten when the song changes`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Zweites Lied", "zweites")) }
        var answering = true
        val ma = maServer({ listOf("Erstes Lied", "Eingeschoben", "Zweites Lied") }, { answering })
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), maQueueMs = 300L)
            s.start()
            withTimeout(5_000) { s.state.first { it.next?.title == "Eingeschoben" } }
            // Music Assistant falls silent; Home Assistant alone tells what comes next.
            answering = false
            queueAnswer = { queueOf(item("Zweites Lied", "zweites"), item("Erstes Lied", "erstes")) }
            play("Zweites Lied")
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Zweites Lied" && it.queueKnown } }
            // The first song again, another song after it this time.
            queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Drittes Lied", "drittes")) }
            play("Erstes Lied")
            val again = withTimeout(5_000) { s.state.first { it.player?.track?.title == "Erstes Lied" && it.queueKnown } }
            assertThat(again.next?.title).isEqualTo("Drittes Lied")
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `without Music Assistant itself it is never asked what comes next`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Zweites Lied", "zweites")) }
        val s = source(maQueueMs = 100L)
        s.start()
        withTimeout(5_000) { s.state.first { it.next?.title == "Zweites Lied" } }
        delay(600)
        assertThat(s.state.value.next!!.title).isEqualTo("Zweites Lied")
        assertThat(maCommands).isEmpty()
        s.stop()
    }

    @Test
    fun `a song waits for a silent queue only a moment`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueSilent = true
        val s = source(detailsWaitMs = 800L)
        val started = System.currentTimeMillis()
        s.start()
        val track = withTimeout(5_000) { s.state.first { it.player?.track != null } }.player!!.track!!
        // Shown as Home Assistant has it, once the wait is over.
        assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(750L)
        assertThat(track.artworkUrl).isNotNull().contains("/api/media_player_proxy/")
        s.stop()
    }

    @Test
    fun `the words of the song that plays come by its address, the next song's ahead of it`() = runBlocking {
        player = """{"media_title":"Erstes Lied","media_artist":"A","media_content_id":"library://track/1","app_id":"music_assistant",
            "mass_player_type":"player","active_queue":"q1"}"""
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), item("Zweites Lied", "zweites")) }
        val asked = CopyOnWriteArrayList<String>()
        val ma = MockWebServer()
        ma.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                val command = body["command"]!!.jsonPrimitive.content
                maCommands += command
                return when (command) {
                    // The song that plays brings its words; the next one does not.
                    "player_queues/items" -> MockResponse.Builder().body(
                        """[{"queue_id":"q1","queue_item_id":"a","name":"Erstes Lied","duration":200,
                              "media_item":{"media_type":"track","name":"Erstes Lied","uri":"library://track/1","artists":[{"name":"A"}],
                                "metadata":{"lrc_lyrics":"[00:05.00]Erste Zeile\n[00:09.00]Zweite Zeile"}}},
                            {"queue_id":"q1","queue_item_id":"b","name":"Zweites Lied","duration":200,
                              "media_item":{"media_type":"track","name":"Zweites Lied","uri":"library://track/2","artists":[{"name":"A"}],"metadata":{}}}]""",
                    ).build()
                    "metadata/get_track_lyrics" -> {
                        asked += body["args"]!!.jsonObject["track"]!!.jsonObject["uri"]!!.jsonPrimitive.content
                        MockResponse.Builder().body("""[null,"[00:03.00]Noch eine Zeile\n[00:07.00]Und noch eine"]""").build()
                    }
                    else -> MockResponse.Builder().body("[]").build()
                }
            }
        }
        ma.start()
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"))
            s.start()
            val first = withTimeout(5_000) { s.state.first { it.lyrics != null } }
            val known = first.lyrics!!
            assertThat(known.key).isEqualTo(first.player!!.track!!.key)
            assertThat(known.lyrics!!.lines.map { it.text }).containsExactly("Erste Zeile", "Zweite Zeile")
            // Asked ahead for the song up next, and only for it.
            withTimeout(5_000) { while (asked.isEmpty()) delay(20) }
            assertThat(asked).containsExactlyInAnyOrder("library://track/2")
            delay(300)
            // It starts: its words are there with it.
            queueAnswer = { queueOf(item("Zweites Lied", "zweites"), null) }
            report("media_title" to "Zweites Lied", "media_content_id" to "library://track/2")
            val second = withTimeout(5_000) { s.state.first { it.player?.track?.title == "Zweites Lied" } }
            val next = second.lyrics!!
            assertThat(next.key).isEqualTo(second.player!!.track!!.key)
            assertThat(next.lyrics!!.lines.first().text).isEqualTo("Noch eine Zeile")
            delay(300)
            assertThat(asked).hasSize(1)
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `without Music Assistant itself nothing is known of the words`() = runBlocking {
        player = """{"media_title":"Erstes Lied","media_artist":"A","media_content_id":"library://track/1","app_id":"music_assistant","mass_player_type":"player"}"""
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.queueKnown } }
        assertThat(s.state.value.lyrics).isNull()
        s.stop()
    }

    @Test
    fun `the songs of a player without a queue come at once`() = runBlocking {
        player = maPlayer("Erstes Lied", "c1")
        queueAnswer = null
        val s = source()
        s.start()
        withTimeout(5_000) { s.state.first { it.player?.track != null } }
        withTimeout(5_000) { while (queueCalls() < 1) delay(20) }
        val before = queueCalls()
        play("Zweites Lied")
        withTimeout(5_000) { s.state.first { it.player?.track?.title == "Zweites Lied" } }
        // Not held for a read: the next one only starts after the burst has settled.
        assertThat(queueCalls()).isEqualTo(before)
        s.stop()
    }


    @Test
    fun `a weather entity's wind is read in its own unit`() {
        assertThat(kmh(10.0, null)).isEqualTo(10.0)
        assertThat(kmh(10.0, "m/s")).isNotNull().isCloseTo(36.0, 0.01)
        assertThat(kmh(10.0, "mph")).isNotNull().isCloseTo(16.09, 0.01)
        assertThat(kmh(10.0, "furlongs")).isNull()
    }

    @Test
    fun `music on this TV shows instead of the chosen speaker, on the same connection, and the chosen one comes back`() = runBlocking {
        registry = """[{"ei":"media_player.wz","pl":"music_assistant","di":"d1"},{"ei":"media_player.tv","pl":"music_assistant","di":"d2"}]"""
        val ma = speakerServer()
        try {
            val speaker = MutableStateFlow(SpeakerStatus(SpeakerLink.CONNECTED, clientId = "Q2xpZW50SWQ"))
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), speaker = speaker)
            s.start()
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Erstes Lied" } }
            withTimeout(5_000) { s.state.first { it.ownSpeaker == "media_player.tv" } }
            assertThat(s.state.value.speaker).isEqualTo("media_player.wz")
            assertThat(maCommands).contains("players/all")
            // Nothing plays here yet: the chosen speaker stays.
            withTimeout(5_000) { while (triggers.none { "media_player.tv" in it.second }) delay(20) }
            delay(300)
            assertThat(s.state.value.speaker).isEqualTo("media_player.wz")

            reportTv("playing", "Hier")
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Hier" } }
            assertThat(s.state.value.speaker).isEqualTo("media_player.tv")
            assertThat(s.state.value.player?.friendlyName).isEqualTo("Fernseher")
            // The volume keys and every other command go to what shows.
            s.send(io.github.hazymorning.klangbild.data.RemoteCommand.VOLUME_UP)
            withTimeout(5_000) { while (calls.none { it["service"]?.jsonPrimitive?.content == "volume_up" }) delay(20) }
            assertThat(calls.first { it["service"]?.jsonPrimitive?.content == "volume_up" }["target"].toString()).isEqualTo("""{"entity_id":"media_player.tv"}""")

            // Paused while the chosen speaker plays, the chosen one comes back; music here again
            // takes the screen again, and once it has stopped the chosen speaker comes back after a moment.
            reportTv("paused", "Hier")
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Erstes Lied" } }
            assertThat(s.state.value.speaker).isEqualTo("media_player.wz")
            reportTv("playing", "Wieder hier")
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Wieder hier" } }
            reportTv("idle", null)
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Erstes Lied" } }
            assertThat(s.state.value.speaker).isEqualTo("media_player.wz")
            assertThat(sockets).hasSize(1)

            // Switched off: this TV is no longer a speaker.
            speaker.value = SpeakerStatus()
            withTimeout(5_000) { s.state.first { it.ownSpeaker == null } }
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `this TV chosen as the speaker shows its own once it is found, and nothing before`() = runBlocking {
        registry = """[{"ei":"media_player.wz","pl":"music_assistant","di":"d1"},{"ei":"media_player.tv","pl":"music_assistant","di":"d2"}]"""
        tvState = "playing"
        tv = """{"friendly_name":"Fernseher","media_title":"Hier","media_artist":"B"}"""
        val ma = speakerServer()
        try {
            val speaker = MutableStateFlow(SpeakerStatus(SpeakerLink.CONNECTING))
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), entity = EntityId.THIS_TV, speaker = speaker)
            s.start()
            withTimeout(5_000) { s.state.first { it.link == io.github.hazymorning.klangbild.domain.LinkState.CONNECTED } }
            delay(300)
            assertThat(s.state.value.player).isNull()
            assertThat(s.state.value.entityMissing).isFalse()
            assertThat(triggers.first().second).doesNotContain(EntityId.THIS_TV)
            speaker.value = SpeakerStatus(SpeakerLink.CONNECTED, clientId = "Q2xpZW50SWQ")
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Hier" } }
            assertThat(s.state.value.speaker).isEqualTo("media_player.tv")
            s.stop()
        } finally {
            ma.close()
        }
    }
    @Test
    fun `this TV is found also when Home Assistant's first answer about its registry fails`() = runBlocking {
        registryErrors = 1
        registry = """[{"ei":"media_player.wz","pl":"music_assistant","di":"d1"},{"ei":"media_player.tv","pl":"music_assistant","di":"d2"}]"""
        tvState = "playing"
        tv = """{"friendly_name":"Fernseher","media_title":"Hier","media_artist":"B"}"""
        val ma = speakerServer()
        try {
            val speaker = MutableStateFlow(SpeakerStatus(SpeakerLink.CONNECTED, clientId = "Q2xpZW50SWQ"))
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), entity = EntityId.THIS_TV, speaker = speaker)
            s.start()
            withTimeout(5_000) { s.state.first { it.player?.track?.title == "Hier" } }
            assertThat(registryErrors).isEqualTo(0)
            assertThat(s.state.value.speaker).isEqualTo("media_player.tv")
            s.stop()
        } finally {
            ma.close()
        }
    }

    @Test
    fun `a Music Assistant song's position comes from its queue to a fraction of a second while Home Assistant agrees`() = runBlocking {
        // Home Assistant: 61 s, a second ago, in whole seconds.
        val reportedAtMs = System.currentTimeMillis() - 1_000
        player = """{"media_title":"Erstes Lied","media_artist":"A","app_id":"music_assistant","mass_player_type":"player",
            "media_position":61,"media_position_updated_at":"${java.time.Instant.ofEpochMilli(reportedAtMs)}","media_duration":200}"""
        queueAnswer = { queueOf(item("Erstes Lied", "erstes"), null) }
        // Music Assistant's clock runs 100 s behind this one; its queue is a quarter of a second ahead of
        // Home Assistant, to the millisecond, and says where it stood half a second before it answers.
        var ahead = 0.25
        var answeredAt = 0L
        var maState = "playing"
        val ma = MockWebServer()
        ma.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val command = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["command"]!!.jsonPrimitive.content
                maCommands += command
                val local = System.currentTimeMillis()
                val maNow = local / 1_000.0 - 100
                return MockResponse.Builder().body(
                    when (command) {
                        "time" -> "$maNow"
                        "player_queues/items" -> "[${maItem("Erstes Lied")}]"
                        "player_queues/get" -> {
                            answeredAt = local
                            val elapsed = 61 + ahead + (local - 500 - reportedAtMs) / 1_000.0
                            """{"queue_id":"q1","current_index":0,"items":1,"current_item":${maItem("Erstes Lied")},"next_item":null,
                                "state":"$maState","elapsed_time":$elapsed,"elapsed_time_last_updated":${maNow - 0.5},"playback_speed":1.0}"""
                        }
                        else -> "[]"
                    },
                ).build()
            }
        }
        ma.start()
        try {
            val s = source(ma = MaClient(http, ma.url("/").toString().trimEnd('/'), "ma-token"), maQueueMs = 300L)
            s.start()
            val precise = withTimeout(5_000) { s.state.first { it.player?.positionAtEpochMs.let { at -> at != null && at != reportedAtMs } } }
            val p = precise.player!!
            assertThat(maCommands).contains("time")
            // Valid half a second before Music Assistant answered, on Home Assistant's clock (here this one's).
            assertThat(precise.skewMs).isEqualTo(0L)
            assertThat(p.positionAtEpochMs!! - (answeredAt - 500)).isBetween(-50L, 50L)
            // A quarter of a second ahead of Home Assistant's whole seconds, as Music Assistant says, as
            // closely as its clock is known (half a round trip).
            val home = 61_000 + (p.positionAtEpochMs!! - reportedAtMs)
            assertThat(p.positionMs!! - home).isBetween(200L, 300L)
            // A pause lets go of Music Assistant's position: playing again, Home Assistant's stands
            // until Music Assistant says it plays too. Its states are stamped with this clock's time
            // now, as Home Assistant's would be: their stamps tell Klangbild how its clock stands.
            stamp = { java.time.Instant.now().toString() }
            maState = "paused"
            playerStatus = "paused"
            report()
            withTimeout(5_000) { s.state.first { it.player?.status == PlayerStatus.PAUSED } }
            playerStatus = "playing"
            report()
            withTimeout(5_000) { s.state.first { it.player?.status == PlayerStatus.PLAYING } }
            repeat(3) {
                assertThat(s.state.value.player!!.positionAtEpochMs).isEqualTo(reportedAtMs)
                delay(300)
            }
            maState = "playing"
            withTimeout(5_000) { s.state.first { it.player?.positionAtEpochMs.let { at -> at != null && at != reportedAtMs } } }
            // Music Assistant far from Home Assistant (a jump it has not told yet): Home Assistant's stays.
            ahead = -50.0
            withTimeout(5_000) { s.state.first { it.player?.positionAtEpochMs == reportedAtMs } }
            assertThat(s.state.value.player!!.positionMs).isEqualTo(61_000L)
            s.stop()
        } finally {
            ma.close()
        }
    }
}
