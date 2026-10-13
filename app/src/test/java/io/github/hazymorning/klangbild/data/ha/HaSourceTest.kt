package io.github.hazymorning.klangbild.data.ha

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.domain.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The source against a stand-in Home Assistant (and Music Assistant): what it asks, and how
 * often. Quick skips, players without a queue, jumps, a book's chapters and speed.
 */
class HaSourceTest {
    private lateinit var ha: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
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

    @Before
    fun setUp() {
        ha = MockWebServer()
        ha.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/websocket" -> MockResponse.Builder().webSocketUpgrade(FakeHa()).build()
                    path == "/api/states/media_player.wz" -> MockResponse.Builder().body(playerState(Json.parseToJsonElement(player).jsonObject)).build()
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

    @After
    fun tearDown() {
        scope.cancel()
        ha.close()
    }

    @Synchronized
    private fun playerState(attributes: JsonObject): String {
        val time = "2026-10-10T18:00:%02d+00:00".format(changes++ % 60)
        return """{"entity_id":"media_player.wz","state":"playing","attributes":$attributes,
            "last_changed":"$time","last_updated":"$time","context":{"id":"x","parent_id":null,"user_id":null}}"""
    }

    private inner class FakeHa : WebSocketListener() {
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
                    ok()
                }
                "config/entity_registry/list_for_display" -> ok("""{"entity_categories":{},"entities":$registry}""")
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

    private fun source(
        ma: MaClient? = null,
        refreshMs: Long = 60_000L,
        partyMs: Long = HaSource.PARTY_MS,
        detailsWaitMs: Long = HaSource.DETAILS_WAIT_MS,
        queueSettleMs: Long = HaSource.QUEUE_SETTLE_MS,
        maQueueMs: Long = HaSource.MA_QUEUE_MS,
    ) = HaSource(
        http,
        scope,
        HaConfig(HaAuth(http, ha.url("/").toString().trimEnd('/'), HaCredentials.LongLived("good"))),
        "media_player.wz",
        ma = ma,
        queueRefreshMs = refreshMs,
        partyMs = partyMs,
        actionAnswerMs = 500L,
        detailsWaitMs = detailsWaitMs,
        queueSettleMs = queueSettleMs,
        maQueueMs = maQueueMs,
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
        assertThat(calls.map { it["service"]!!.jsonPrimitive.content }).containsExactly("media_play_pause", "media_next_track").inOrder()
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
            assertThat(track.artists).containsExactly("Franz Kafka")
            assertThat(track.chapters.map { it.name }).containsExactly("Verhaftung", "Gespräch mit Frau Grubach").inOrder()
            assertThat(p.speed).isWithin(1e-6f).of(1.25f)
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
        assertThat(t.artworkUrl).endsWith("cache=song")
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
        assertThat(picture()).endsWith("cache=station")
        // Music Assistant has found the song's picture.
        report("entity_picture" to "https://img.example/love-and-hate.jpg")
        withTimeout(5_000) { s.state.first { it.player?.track?.artworkUrl?.contains("love-and-hate") == true } }
        // For a while it shows the station's again (with a new token): the song's stays.
        report("entity_picture" to stationPicture.replace("token=t", "token=u"))
        delay(400)
        assertThat(picture()).contains("love-and-hate")
        // The next song starts with the station's picture.
        report("media_title" to "Spinning", "media_artist" to "Other", "entity_picture" to stationPicture)
        withTimeout(5_000) { s.state.first { it.player?.track?.title == "Spinning" } }
        assertThat(picture()).endsWith("cache=station")
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
            .containsExactly("script.turn_on", "scene.turn_on", "automation.trigger", "input_button.press", "input_boolean.toggle").inOrder()
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
        assertThat(state.precision).containsExactly("sensor.outside", 1)
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
        assertThat(s.favorite()).isTrue()
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
        assertThat(s.favorite()).isFalse()
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
        assertThat(seen.map { it.artworkUrl }.distinct()).containsExactly("https://cdn.example/erstes.jpg")
        assertThat(seen.map { it.year }.distinct()).containsExactly(2024)
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
            assertThat(changed.coming.map { it.title }).containsExactly("Eingeschoben", "Zweites Lied").inOrder()
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
        assertThat(System.currentTimeMillis() - started).isAtLeast(750L)
        assertThat(track.artworkUrl).contains("/api/media_player_proxy/")
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
            assertThat(known.lyrics!!.lines.map { it.text }).containsExactly("Erste Zeile", "Zweite Zeile").inOrder()
            // Asked ahead for the song up next, and only for it.
            withTimeout(5_000) { while (asked.isEmpty()) delay(20) }
            assertThat(asked).containsExactly("library://track/2")
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
        assertThat(kmh(10.0, "m/s")).isWithin(0.01).of(36.0)
        assertThat(kmh(10.0, "mph")).isWithin(0.01).of(16.09)
        assertThat(kmh(10.0, "furlongs")).isNull()
    }
}
