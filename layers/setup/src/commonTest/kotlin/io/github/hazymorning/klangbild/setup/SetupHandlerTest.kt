package io.github.hazymorning.klangbild.setup

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasLength
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.hazymorning.klangbild.config.PlayHere
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.data.ha.HaAuthRejectedException
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaTokens
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.render.QrCode
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class SetupHandlerTest {
    private var stored = Settings(haUrl = "http://192.168.1.5:8123", haToken = "secret-token", entityId = "media_player.alt")
    private val discovered = mutableListOf<Pair<String, HaCredentials>>()
    private val maChecked = mutableListOf<Pair<String, String>>()
    private val exchanged = mutableListOf<Triple<String, String, String>>()
    private val revoked = mutableListOf<Pair<String, String>>()
    private var maAnswer = MaLink.CONNECTED
    private var texts: Texts = TestTexts.german
    private var now = 1_000_000L
    private var exchangeFails = false

    /** While set, swapping an authorization code for tokens waits for it. */
    private var exchangeGate: CompletableDeferred<Unit>? = null

    /** This TV's own speaker in Home Assistant, and its pairing code, while it plays as a speaker. */
    private var own: String? = null
    private var pairing: String? = null

    private val clientId = "http://192.168.1.23:8765/"

    /** What saving also does: on the TV it ends the setup. */
    private var afterSave: suspend () -> Unit = {}

    private val handler: SetupHandler = SetupHandler(
        settings = { stored },
        texts = { texts },
        discover = { url, credentials ->
            discovered += url to credentials
            DiscoverResult.Ok(
                listOf(SpeakerOption("media_player.wz", "Wohnzimmer", "spielt", true)),
                listOf(LightOption("light.tv_left", "TV links", "an"), LightOption("light.tv_right", "TV rechts", "aus")),
                listOf(ActionOption("script.ungroup_speakers", "Lautsprecher trennen"), ActionOption("scene.movie", "Kino")),
            )
        },
        save = {
            stored = it
            afterSave()
        },
        clientId = { clientId },
        exchange = { url, code, client ->
            exchanged += Triple(url, code, client)
            exchangeGate?.await()
            if (exchangeFails) throw HaAuthRejectedException("invalid_request")
            HaTokens("access-$code", "refresh-$code", 1800)
        },
        revoke = { url, token -> revoked += url to token },
        checkMa = { url, token ->
            maChecked += url to token
            maAnswer
        },
        maGuess = { "http://192.168.1.5:8095" },
        ownSpeaker = { own },
        pairingToken = { pairing },
        random = CryptographyRandom,
        now = { now },
        wrongCodeDelayMs = 0L,
    )

    private suspend fun post(path: String, body: String) =
        handler.handle(MiniHttp.Request("POST", path, emptyMap(), emptyMap(), body.encodeToByteArray()))

    private fun json(r: MiniHttp.Response): JsonObject = Json.parseToJsonElement(r.body.decodeToString()).jsonObject

    @Test
    fun `serves the page with the prefilled code`() = runTest {
        val r = handler.handle(MiniHttp.Request("GET", "/", mapOf("code" to "123456"), emptyMap(), ByteArray(0)))
        assertThat(r.status).isEqualTo(200)
        val html = r.body.decodeToString()
        assertThat(html).contains("value=\"123456\"")
        assertThat(html).contains("Langlebiges Zugangstoken")
        assertThat(html).contains("<html lang=\"de\">")
        val bad = handler.handle(MiniHttp.Request("GET", "/", mapOf("code" to "<script>"), emptyMap(), ByteArray(0)))
        assertThat(bad.body.decodeToString()).doesNotContain("<script>\"")
    }

    @Test
    fun `api calls need the code and never return the token`() = runTest {
        assertThat(post("/api/state", """{"code":"000000x"}""").status).isEqualTo(403)
        val r = post("/api/state", """{"code":"${handler.code}"}""")
        assertThat(r.status).isEqualTo(200)
        val body = r.body.decodeToString()
        assertThat(body).doesNotContain("secret-token")
        assertThat(json(r)["auth"]!!.jsonPrimitive.content).isEqualTo("token")
    }

    @Test
    fun `too many wrong codes rotate the code`() = runTest {
        val first = handler.code
        repeat(SetupHandler.MAX_FAILURES) { post("/api/state", """{"code":"999999"}""") }
        // The chance that the new random code equals the old one is 1e-6.
        assertThat(handler.code).isNotEqualTo(first)
    }

    @Test
    fun `discover reuses the stored token for the same server`() = runTest {
        val r = post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}""")
        assertThat(json(r)["ok"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(discovered.single()).isEqualTo("http://192.168.1.5:8123" to HaCredentials.LongLived("secret-token"))
        val other = post("/api/discover", """{"code":"${handler.code}","url":"10.0.0.9:8123","token":""}""")
        assertThat(other.status).isEqualTo(400)
    }

    @Test
    fun `save validates and stores`() = runTest {
        val bad = post("/api/save", """{"code":"${handler.code}","url":"http://ha:8123","entity":"Wohnzimmer"}""")
        assertThat(bad.status).isEqualTo(400)
        val ok = post(
            "/api/save",
            """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"","entity":"media_player.wz",
               "sleepMinutes":25,"sleepAction":"close","clock":false,"remote":false}""",
        )
        assertThat(ok.status).isEqualTo(200)
        assertThat(stored.haUrl).isEqualTo("http://192.168.1.5:8123")
        assertThat(stored.haToken).isEqualTo("secret-token")
        assertThat(stored.entityId).isEqualTo("media_player.wz")
        assertThat(stored.sleepAfterMinutes).isEqualTo(25)
        assertThat(stored.sleepAction).isEqualTo(SleepAction.CLOSE)
        assertThat(stored.showClock).isFalse()
        assertThat(stored.remoteControl).isFalse()
    }

    @Test
    fun `a stored token only goes to the address it was made for`() = runTest {
        val moved = post("/api/save", """{"code":"${handler.code}","url":"10.0.0.9:8123","token":"","entity":"media_player.wz"}""")
        assertThat(moved.status).isEqualTo(400)
        assertThat(stored.haUrl).isEqualTo("http://192.168.1.5:8123")
        val withToken = post("/api/save", """{"code":"${handler.code}","url":"10.0.0.9:8123","token":"new","entity":"media_player.wz"}""")
        assertThat(withToken.status).isEqualTo(200)
        assertThat(stored.haToken).isEqualTo("new")
    }

    @Test
    fun `music assistant is checked before it is stored, and leaving it empty removes it`() = runTest {
        val state = json(post("/api/state", """{"code":"${handler.code}"}"""))
        assertThat(state["maUrl"]!!.jsonPrimitive.content).isEqualTo("http://192.168.1.5:8095")
        assertThat(state["hasMaToken"]!!.jsonPrimitive.content).isEqualTo("false")

        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        assertThat(post("/api/save", """{$base,"maUrl":"192.168.1.5:8095","maToken":""}""").status).isEqualTo(400)
        maAnswer = MaLink.AUTH_FAILED
        assertThat(post("/api/save", """{$base,"maUrl":"192.168.1.5:8095","maToken":"ma-secret"}""").status).isEqualTo(400)
        assertThat(stored.maUrl).isNull()
        maAnswer = MaLink.CONNECTED
        assertThat(post("/api/save", """{$base,"maUrl":"192.168.1.5:8095","maToken":"ma-secret"}""").status).isEqualTo(200)
        assertThat(stored.maUrl).isEqualTo("http://192.168.1.5:8095")
        assertThat(stored.maToken).isEqualTo("ma-secret")
        assertThat(maChecked.last()).isEqualTo("http://192.168.1.5:8095" to "ma-secret")
        assertThat(post("/api/state", """{"code":"${handler.code}"}""").body.decodeToString()).doesNotContain("ma-secret")

        // Saving again unchanged does not ask Music Assistant again; an empty address removes it.
        val checks = maChecked.size
        assertThat(post("/api/save", """{$base,"maUrl":"http://192.168.1.5:8095","maToken":""}""").status).isEqualTo(200)
        assertThat(maChecked).hasSize(checks)
        assertThat(post("/api/save", """{$base,"maUrl":""}""").status).isEqualTo(200)
        assertThat(stored.maUrl).isNull()
        assertThat(stored.maToken).isNull()
    }

    @Test
    fun `this TV plays as a speaker once ticked, or once it is chosen as the speaker followed`() = runTest {
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","token":"""""
        // "This TV" needs Music Assistant.
        assertThat(post("/api/save", """{$base,"entity":"this_tv","tvSpeaker":true}""").status).isEqualTo(400)
        assertThat(post("/api/save", """{$base,"entity":"media_player.wz","maUrl":"192.168.1.5:8095","maToken":"ma-secret","tvSpeaker":true}""").status).isEqualTo(200)
        assertThat(stored.playHere).isEqualTo(PlayHere.OPEN)
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["tvSpeaker"]!!.jsonPrimitive.content).isEqualTo("true")
        // Ticked while it plays always, it goes on doing so: when it plays is chosen on the TV.
        stored = stored.copy(playHere = PlayHere.ALWAYS)
        assertThat(post("/api/save", """{$base,"entity":"media_player.wz","maUrl":"http://192.168.1.5:8095","tvSpeaker":true}""").status).isEqualTo(200)
        assertThat(stored.playHere).isEqualTo(PlayHere.ALWAYS)
        assertThat(post("/api/save", """{$base,"entity":"media_player.wz","maUrl":"http://192.168.1.5:8095","tvSpeaker":false}""").status).isEqualTo(200)
        assertThat(stored.tvSpeaker).isFalse()
        // Chosen, it plays here, and the speaker followed before is kept to go back to.
        assertThat(post("/api/save", """{$base,"entity":"this_tv","maUrl":"http://192.168.1.5:8095","tvSpeaker":false}""").status).isEqualTo(200)
        assertThat(stored.entityId).isEqualTo("this_tv")
        assertThat(stored.speakerOn).isTrue()
        assertThat(stored.otherSpeaker).isEqualTo("media_player.wz")
        // A page that does not know the checkbox leaves it as it was.
        assertThat(post("/api/save", """{$base,"entity":"media_player.wz","maUrl":"http://192.168.1.5:8095"}""").status).isEqualTo(200)
        assertThat(stored.entityId).isEqualTo("media_player.wz")
        assertThat(stored.tvSpeaker).isTrue()
    }

    @Test
    fun `the pairing code is given only with the code, and the TV's own speaker is left out of the list`() = runTest {
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["pairingToken"]!!.jsonPrimitive.content).isEmpty()
        pairing = "SP:0PAIRINGCODE"
        assertThat(post("/api/state", """{"code":"999999"}""").status).isEqualTo(403)
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["pairingToken"]!!.jsonPrimitive.content).isEqualTo("SP:0PAIRINGCODE")
        assertThat(get("/").body.decodeToString()).doesNotContain("SP:0PAIRINGCODE")
        suspend fun speakers() = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))["speakers"].toString()
        assertThat(speakers()).contains("media_player.wz")
        own = "media_player.wz"
        assertThat(speakers()).isEqualTo("[]")
    }

    @Test
    fun `mini http parses requests and enforces limits`() = runTest {
        val raw = "POST /api/save?x=1&y=a%20b HTTP/1.1\r\nHost: tv\r\nContent-Length: 4\r\nContent-Type: application/json\r\n\r\n{\"a\"".encodeToByteArray()
        val req = MiniHttp.read(Buffer().apply { write(raw) })
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/api/save")
        assertThat(req.query).isEqualTo(mapOf("x" to "1", "y" to "a b"))
        assertThat(req.headers["content-type"]).isEqualTo("application/json")
        assertThat(req.bodyText).isEqualTo("{\"a\"")

        val tooBig = "POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".encodeToByteArray()
        assertThat(runCatching { MiniHttp.read(Buffer().apply { write(tooBig) }) }.exceptionOrNull())
            .isNotNull().isInstanceOf<MiniHttp.BadRequest>()
        val chunked = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".encodeToByteArray()
        assertThat(runCatching { MiniHttp.read(Buffer().apply { write(chunked) }) }.exceptionOrNull())
            .isNotNull().isInstanceOf<MiniHttp.BadRequest>()

        val out = Buffer()
        MiniHttp.write(out, MiniHttp.Response.json(200, "{}"))
        val text = out.readString()
        assertThat(text).startsWith("HTTP/1.1 200 OK\r\n")
        assertThat(text).contains("Content-Length: 2\r\n")
        assertThat(text).contains("Cache-Control: no-store")
    }

    @Test
    fun `speaker list prefers music assistant and hides displays`() = runTest {
        val r = SetupServer.parseSpeakers(
            """[
              {"entity_id":"light.x","state":"on","attributes":{}},
              {"entity_id":"media_player.kueche","state":"idle","attributes":{"friendly_name":"Küche"}},
              {"entity_id":"media_player.wz","state":"playing","attributes":{"friendly_name":"Wohnzimmer","mass_player_type":"player"}},
              {"entity_id":"media_player.tv_display","state":"idle","attributes":{"friendly_name":"TV","mass_player_type":"display","device_class":"tv"}}
            ]""",
            texts,
        ) as DiscoverResult.Ok
        assertThat(r.speakers.map { it.entityId }).containsExactly("media_player.wz", "media_player.kueche")
        assertThat(r.speakers.first().state).isEqualTo("spielt")
    }

    @Test
    fun `the lights are offered, stored when the page showed them and kept when it did not`() = runTest {
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["lights"].toString()).contains("light.tv_left")
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        assertThat(post("/api/save", """{$base,"lightsLeft":"light.tv_left","lightsCenter":"","lightsRight":"light.tv_right"}""").status).isEqualTo(200)
        assertThat(stored.roomLights).isEqualTo(io.github.hazymorning.klangbild.config.RoomLights("light.tv_left", null, "light.tv_right"))
        val state = json(post("/api/state", """{"code":"${handler.code}"}"""))
        assertThat(state["lightsRight"]!!.jsonPrimitive.content).isEqualTo("light.tv_right")
        // Saved without the lights section (nothing found yet): the lights stay.
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.roomLights.ids).containsExactlyInAnyOrder("light.tv_left", "light.tv_right")
        // Only lights.
        assertThat(post("/api/save", """{$base,"lightsLeft":"switch.socket"}""").status).isEqualTo(400)
        assertThat(post("/api/save", """{$base,"lightsLeft":"","lightsCenter":"","lightsRight":""}""").status).isEqualTo(200)
        assertThat(stored.roomLights.isSet).isFalse()
    }

    @Test
    fun `the quick settings' own buttons are offered, stored when the page showed them and kept when it did not`() = runTest {
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["actions"].toString()).contains("script.ungroup_speakers")
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        val two = """[{"name":"Trennen","entity":"script.ungroup_speakers"},{"name":"","entity":"scene.movie"}]"""
        assertThat(post("/api/save", """{$base,"actions":$two}""").status).isEqualTo(200)
        assertThat(stored.quickActions).containsExactly(QuickAction("Trennen", "script.ungroup_speakers"), QuickAction("Movie", "scene.movie"))
        val state = json(post("/api/state", """{"code":"${handler.code}"}"""))
        assertThat(state["actions"].toString()).isEqualTo("""[{"name":"Trennen","entity":"script.ungroup_speakers"},{"name":"Movie","entity":"scene.movie"}]""")
        // Saved without the section (nothing found yet): the buttons stay.
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.quickActions).hasSize(2)
        // Nothing that can't be a button is saved: a lock, a media player, a malformed list.
        assertThat(post("/api/save", """{$base,"actions":[{"name":"Tür","entity":"lock.front_door"}]}""").status).isEqualTo(400)
        assertThat(post("/api/save", """{$base,"actions":"script.x"}""").status).isEqualTo(400)
        assertThat(stored.quickActions).hasSize(2)
        // At most six; an empty list removes them.
        val many = (1..9).joinToString(",", "[", "]") { """{"name":"$it","entity":"script.s$it"}""" }
        assertThat(post("/api/save", """{$base,"actions":$many}""").status).isEqualTo(200)
        assertThat(stored.quickActions).hasSize(QuickActions.MAX)
        assertThat(post("/api/save", """{$base,"actions":[]}""").status).isEqualTo(200)
        assertThat(stored.quickActions).isEmpty()
        // The list from Home Assistant: what a button can work, scripts first, by name.
        val r = SetupServer.parseSpeakers(
            """[{"entity_id":"switch.socket","state":"on","attributes":{"friendly_name":"Steckdose"}},
               {"entity_id":"lock.door","state":"locked","attributes":{}},
               {"entity_id":"script.b","state":"off","attributes":{"friendly_name":"Beta"}},
               {"entity_id":"script.a","state":"off","attributes":{"friendly_name":"Alpha"}},
               {"entity_id":"scene.kino","state":"2026-10-10T20:00:00+00:00","attributes":{"friendly_name":"Kino"}}]""",
            texts,
        ) as DiscoverResult.Ok
        assertThat(r.actions.map { it.entityId }).containsExactly("script.a", "script.b", "scene.kino", "switch.socket")
    }

    @Test
    fun `a button keeps its script's variables and its condition, and the page gets them back`() = runTest {
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        val button = """{"name":"Trennen","entity":"script.ungroup_speakers","variables":{"mode":"all","volume":0.4},
            "when":{"entity":"media_player.wz","attribute":"group_members","test":"above","value":"1"}}"""
        assertThat(post("/api/save", """{$base,"actions":[$button]}""").status).isEqualTo(200)
        val a = stored.quickActions.single()
        assertThat(a.variables.toString()).isEqualTo("""{"mode":"all","volume":0.4}""")
        assertThat(a.condition).isEqualTo(io.github.hazymorning.klangbild.domain.Condition("media_player.wz", "group_members", io.github.hazymorning.klangbild.domain.Condition.Test.ABOVE, "1"))
        val state = json(post("/api/state", """{"code":"${handler.code}"}"""))
        assertThat(state["actions"].toString()).contains(""""variables":{"mode":"all","volume":0.4}""")
        assertThat(state["actions"].toString()).contains(""""when":{"entity":"media_player.wz","attribute":"group_members","test":"above","value":"1"}""")
        // Above and below need a number.
        val loose = """{"name":"x","entity":"script.ungroup_speakers","when":{"entity":"sensor.t","test":"above","value":"warm"}}"""
        assertThat(post("/api/save", """{$base,"actions":[$loose]}""").status).isEqualTo(400)
    }

    @Test
    fun `values for the status line are stored when the page showed them and kept when it did not`() = runTest {
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        val two = """[{"name":"Außen","entity":"sensor.outside"},{"name":"","entity":"weather.home","attribute":"temperature"}]"""
        assertThat(post("/api/save", """{$base,"values":$two}""").status).isEqualTo(200)
        assertThat(stored.ownValues).containsExactly(
            io.github.hazymorning.klangbild.domain.OwnValue("Außen", "sensor.outside"),
            io.github.hazymorning.klangbild.domain.OwnValue("", "weather.home", "temperature"),
        )
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["values"].toString())
            .isEqualTo("""[{"name":"Außen","entity":"sensor.outside"},{"name":"","entity":"weather.home","attribute":"temperature"}]""")
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.ownValues).hasSize(2)
        assertThat(post("/api/save", """{$base,"values":[{"name":"x","entity":"not an entity"}]}""").status).isEqualTo(400)
        val many = (1..5).joinToString(",", "[", "]") { """{"name":"$it","entity":"sensor.s$it"}""" }
        assertThat(post("/api/save", """{$base,"values":$many}""").status).isEqualTo(200)
        assertThat(stored.ownValues).hasSize(io.github.hazymorning.klangbild.config.OwnValues.MAX)
        assertThat(post("/api/save", """{$base,"values":[]}""").status).isEqualTo(200)
        assertThat(stored.ownValues).isEmpty()
    }

    @Test
    fun `the speakers in other rooms are stored when the page showed them, without the one chosen`() = runTest {
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        val rooms = """["media_player.kitchen","media_player.wz","media_player.study","media_player.kitchen"]"""
        assertThat(post("/api/save", """{$base,"otherRooms":$rooms}""").status).isEqualTo(200)
        assertThat(stored.otherRooms).containsExactly("media_player.kitchen", "media_player.study")
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["otherRooms"].toString())
            .isEqualTo("""["media_player.kitchen","media_player.study"]""")
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.otherRooms).hasSize(2)
        assertThat(post("/api/save", """{$base,"otherRooms":["light.kitchen"]}""").status).isEqualTo(400)
        assertThat(post("/api/save", """{$base,"otherRooms":[]}""").status).isEqualTo(200)
        assertThat(stored.otherRooms).isEmpty()
    }

    @Test
    fun `the presence sensors are stored when the page showed them, and only sensors that are on or off`() = runTest {
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        assertThat(post("/api/save", """{$base,"presence":["binary_sensor.couch","binary_sensor.couch","binary_sensor.door"]}""").status).isEqualTo(200)
        assertThat(stored.presenceSensors).containsExactly("binary_sensor.couch", "binary_sensor.door")
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["presence"].toString())
            .isEqualTo("""["binary_sensor.couch","binary_sensor.door"]""")
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.presenceSensors).hasSize(2)
        assertThat(post("/api/save", """{$base,"presence":["sensor.lux"]}""").status).isEqualTo(400)
        assertThat(post("/api/save", """{$base,"presence":[]}""").status).isEqualTo(200)
        assertThat(stored.presenceSensors).isEmpty()
    }

    @Test
    fun `binary sensors are offered for presence, presence sensors first`() = runTest {
        val found = SetupServer.parseSpeakers(
            """[{"entity_id":"binary_sensor.door","state":"off","attributes":{"friendly_name":"Door","device_class":"door"}},
               {"entity_id":"binary_sensor.hall","state":"on","attributes":{"friendly_name":"Hall","device_class":"motion"}},
               {"entity_id":"binary_sensor.couch","state":"off","attributes":{"friendly_name":"Couch","device_class":"occupancy"}},
               {"entity_id":"sensor.lux","state":"12","attributes":{}}]""",
            texts,
        ) as DiscoverResult.Ok
        assertThat(found.sensors.map { it.entityId }).containsExactly("binary_sensor.couch", "binary_sensor.hall", "binary_sensor.door")
    }

    @Test
    fun `every entity is offered for values and conditions, without what only describes it`() = runTest {
        val r = SetupServer.parseSpeakers(
            """[{"entity_id":"sensor.outside","state":"12.345","attributes":{"friendly_name":"Outside","unit_of_measurement":"°C","device_class":"temperature"}},
               {"entity_id":"media_player.wz","state":"playing","attributes":{"friendly_name":"Wohnzimmer","group_members":["media_player.wz","media_player.k"],
                 "source_list":["a","b"],"volume_level":0.35,"media_title":"${"x".repeat(80)}","entity_picture":"/api/x","nested":{"a":1}}}]""",
            texts,
        ) as DiscoverResult.Ok
        val outside = r.entities.first { it.entityId == "sensor.outside" }
        assertThat(outside.unit).isEqualTo("°C")
        assertThat(outside.attributes).isEmpty()
        val wz = r.entities.first { it.entityId == "media_player.wz" }
        assertThat(wz.lists).isEqualTo(mapOf("group_members" to 2))
        assertThat(wz.attributes.keys).containsExactlyInAnyOrder("volume_level", "media_title")
        assertThat(wz.attributes.getValue("media_title")).hasLength(40)
        // Sent to the page with the rest.
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["entities"].toString()).isEqualTo("[]")
    }

    @Test
    fun `a script's fields are read from Home Assistant's actions`() = runTest {
        val services = """[{"domain":"light","services":{"turn_on":{"fields":{"brightness":{}}}}},
            {"domain":"script","services":{
              "group":{"name":"Group","fields":{
                "player":{"selector":{"entity":{"filter":[{"domain":"media_player"}]}}},
                "mode":{"name":"Mode","required":true,"default":"join","selector":{"select":{"options":["join",{"value":"leave","label":"Leave"}]}}},
                "volume":{"name":"Volume","example":"0.3","selector":{"number":{"min":0,"max":1}}},
                "announce":{"selector":{"boolean":{}}},
                "rooms":{"selector":{"entity":{"multiple":true}}},
                "advanced":{"collapsed":true,"fields":{"delay":{"selector":{"text":{}}}}},
                "bad key":{}
              }},
              "plain":{"name":"Plain"}}}]"""
        val fields = SetupServer.scriptFields(services)
        val group = fields.getValue("group")
        assertThat(group.map { it.key }).containsExactly("player", "mode", "volume", "announce", "rooms", "delay")
        assertThat(group[0].type).isEqualTo(ScriptField.ENTITY)
        assertThat(group[0].domains).containsExactlyInAnyOrder("media_player")
        assertThat(group[1].type).isEqualTo(ScriptField.SELECT)
        assertThat(group[1].options).containsExactly("join" to "join", "leave" to "Leave")
        assertThat(group[1].required).isTrue()
        assertThat(group[1].default.toString()).isEqualTo("\"join\"")
        assertThat(group[2].type).isEqualTo(ScriptField.NUMBER)
        assertThat(group[2].example).isEqualTo("0.3")
        assertThat(group[3].type).isEqualTo(ScriptField.BOOLEAN)
        // Several entities at once: written as a list.
        assertThat(group[4].type).isEqualTo(ScriptField.TEXT)
        assertThat(fields.getValue("plain")).isEmpty()
        // The page gets them with the script.
        val r = SetupServer.parseSpeakers("""[{"entity_id":"script.group","state":"off","attributes":{"friendly_name":"Group"}}]""", texts, services) as DiscoverResult.Ok
        assertThat(r.actions.single().fields.map { it.key }).contains("mode")
        assertThat(SetupServer.scriptFields("not json")).isEmpty()
    }

    @Test
    fun `every light is offered, whatever it can show`() = runTest {
        val r = SetupServer.parseSpeakers(
            """[
              {"entity_id":"light.tv_left","state":"off","attributes":{"friendly_name":"TV links","supported_color_modes":["color_temp","xy"]}},
              {"entity_id":"light.flur","state":"on","attributes":{"friendly_name":"Flur","supported_color_modes":["brightness"]}},
              {"entity_id":"light.band","state":"on","attributes":{"friendly_name":"Band","supported_color_modes":["rgbww"]}},
              {"entity_id":"switch.x","state":"on","attributes":{"supported_color_modes":["xy"]}}
            ]""",
            texts,
        ) as DiscoverResult.Ok
        assertThat(r.lights.map { it.entityId }).containsExactly("light.band", "light.flur", "light.tv_left")
        assertThat(r.lights.last().state).isEqualTo("aus")
        // In English the states are English words.
        val en = SetupServer.parseSpeakers("""[{"entity_id":"light.flur","state":"on","attributes":{}}]""", TestTexts.english()) as DiscoverResult.Ok
        assertThat(en.lights.single().state).isEqualTo("on")
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()) =
        handler.handle(MiniHttp.Request("GET", path, query, emptyMap(), ByteArray(0)))

    /** Starts a sign-in on the page; the address of Home Assistant's login page it gets back. */
    private suspend fun startSignIn(url: String = "192.168.1.5:8123") =
        Url(json(post("/api/signin", """{"code":"${handler.code}","url":"$url"}"""))["authorize"]!!.jsonPrimitive.content)

    @Test
    fun `signing in goes through Home Assistant's own login page and back`() = runTest {
        val authorize = startSignIn()
        assertThat(authorize.encodedPath).isEqualTo("/auth/authorize")
        assertThat(authorize.host).isEqualTo("192.168.1.5")
        assertThat(authorize.parameters["client_id"]).isEqualTo(clientId)
        assertThat(authorize.parameters["redirect_uri"]).isEqualTo("http://192.168.1.23:8765/auth/callback")
        val state = authorize.parameters["state"]!!
        assertThat(state.length).isGreaterThanOrEqualTo(40)
        // Home Assistant sends the phone back with a code; the TV swaps it for tokens and the phone goes on.
        val back = get("/auth/callback", mapOf("code" to "abc", "state" to state))
        assertThat(back.status).isEqualTo(303)
        assertThat(back.headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_OK)
        assertThat(exchanged.single()).isEqualTo(Triple("http://192.168.1.5:8123", "abc", clientId))
        // The page learns it is signed in, never the tokens.
        val st = post("/api/state", """{"code":"${handler.code}"}""")
        assertThat(json(st)["auth"]!!.jsonPrimitive.content).isEqualTo("signin")
        assertThat(st.body.decodeToString()).doesNotContain("refresh-abc")
        // Saved with the form, the sign-in replaces the long-lived token.
        val saved = post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"","entity":"media_player.wz"}""")
        assertThat(saved.status).isEqualTo(200)
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.SignedIn("refresh-abc", clientId))
        assertThat(stored.haToken).isNull()
        assertThat(revoked).isEmpty()
    }

    @Test
    fun `the sign-in just saved is never revoked by the end of the setup that saving brings`() = runTest {
        val state = startSignIn().parameters["state"]!!
        get("/auth/callback", mapOf("code" to "abc", "state" to state))
        // On the TV, saving closes the setup screen, and its reset runs before the answer is written.
        afterSave = { handler.reset() }
        val saved = post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"","entity":"media_player.wz"}""")
        assertThat(saved.status).isEqualTo(200)
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.SignedIn("refresh-abc", clientId))
        assertThat(revoked).isEmpty()
    }

    @Test
    fun `a callback without the right state, used twice or too late is refused`() = runTest {
        startSignIn()
        assertThat(get("/auth/callback", mapOf("code" to "abc", "state" to "guessed")).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        // The state of the one sign-in under way, once.
        val state = startSignIn().parameters["state"]!!
        assertThat(get("/auth/callback", mapOf("code" to "abc", "state" to state)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_OK)
        assertThat(get("/auth/callback", mapOf("code" to "abc", "state" to state)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        // Ten minutes and more: Home Assistant's code has expired anyway.
        val late = startSignIn().parameters["state"]!!
        now += SetupHandler.SIGN_IN_MS + 1
        assertThat(get("/auth/callback", mapOf("code" to "def", "state" to late)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        assertThat(exchanged.map { it.second }).containsExactlyInAnyOrder("abc")
        // A code Home Assistant refuses is a failed sign-in.
        exchangeFails = true
        val refused = startSignIn().parameters["state"]!!
        assertThat(get("/auth/callback", mapOf("code" to "ghi", "state" to refused)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        // Starting a sign-in needs the code shown on the TV.
        assertThat(post("/api/signin", """{"code":"000000x","url":"192.168.1.5:8123"}""").status).isEqualTo(403)
    }

    @Test
    fun `a sign-in never saved is revoked, and so is one a new sign-in replaces`() = runTest {
        val first = startSignIn().parameters["state"]!!
        get("/auth/callback", mapOf("code" to "one", "state" to first))
        val second = startSignIn().parameters["state"]!!
        get("/auth/callback", mapOf("code" to "two", "state" to second))
        assertThat(revoked).containsExactlyInAnyOrder("http://192.168.1.5:8123" to "refresh-one")
        // The setup screen closes without saving.
        handler.reset()
        assertThat(revoked).containsExactly("http://192.168.1.5:8123" to "refresh-one", "http://192.168.1.5:8123" to "refresh-two")
    }

    @Test
    fun `a sign-in that arrives after the setup has ended is revoked`() = runTest {
        val state = startSignIn().parameters["state"]!!
        // The setup screen closes while the TV swaps the authorization code for tokens.
        val gate = CompletableDeferred<Unit>()
        exchangeGate = gate
        val back = async { get("/auth/callback", mapOf("code" to "late", "state" to state)) }
        yield()
        handler.reset()
        gate.complete(Unit)
        assertThat(back.await().headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        assertThat(revoked).containsExactly("http://192.168.1.5:8123" to "refresh-late")
        // The next setup knows nothing of it.
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["auth"]!!.jsonPrimitive.content).isEqualTo("token")
    }

    @Test
    fun `an earlier sign-in is revoked when the TV goes over to another one`() = runTest {
        stored = Settings(haUrl = "http://192.168.1.5:8123", haRefreshToken = "old-refresh", haClientId = clientId, entityId = "media_player.wz")
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["auth"]!!.jsonPrimitive.content).isEqualTo("signin")
        // Saved again unchanged: the sign-in stays.
        assertThat(post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz"}""").status).isEqualTo(200)
        assertThat(revoked).isEmpty()
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.SignedIn("old-refresh", clientId))
        // A long-lived token instead: the sign-in is revoked.
        assertThat(post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"long","entity":"media_player.wz"}""").status).isEqualTo(200)
        assertThat(revoked).containsExactlyInAnyOrder("http://192.168.1.5:8123" to "old-refresh")
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.LongLived("long"))
    }

    @Test
    fun `the page speaks the language the TV shows`() = runTest {
        texts = TestTexts.english()
        val html = get("/").body.decodeToString()
        assertThat(html).contains("<html lang=\"en\">")
        assertThat(html).contains("Long-lived access token")
        assertThat(html).doesNotContain("Zugangstoken")
        val r = post("/api/save", """{"code":"${handler.code}","url":"","entity":"x"}""")
        assertThat(json(r)["message"]!!.jsonPrimitive.content).doesNotContain("Adresse")
    }

    @Test
    fun `the page fills every placeholder, and its script uses every word it gets`() = runTest {
        for (words in listOf(TestTexts.german, TestTexts.english())) {
            texts = words
            val html = get("/").body.decodeToString()
            assertThat(Regex("""\{\{\w+}}|__[A-Z_]+__""").find(html)?.value).isNull()
            val given = Json.parseToJsonElement(Regex("""const T = (\{.*?});""").find(html)!!.groupValues[1]).jsonObject.keys
            val used = Regex("""\bT\.(\w+)""").findAll(html).map { it.groupValues[1] }.toSet()
            assertThat(given).containsExactlyInAnyOrder(*(used).toTypedArray())
        }
    }

    @Test
    fun `qr code encodes the url`() = runTest {
        val m = QrCode.matrix("http://192.168.1.23:8765/?code=123456")
        assertThat(m.size).isGreaterThanOrEqualTo(21)
        assertThat(m.all { it.size == m.size }).isTrue()
        // finder pattern corner is dark
        assertThat(m[0][0]).isTrue()
    }
}
