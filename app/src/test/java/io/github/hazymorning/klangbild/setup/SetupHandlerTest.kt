package io.github.hazymorning.klangbild.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.data.ha.HaAuthRejectedException
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaTokens
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
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
                listOf(WeatherOption("weather.forecast_home", "Forecast Home", "teils bewölkt")),
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
            if (exchangeFails) throw HaAuthRejectedException("invalid_request")
            HaTokens("access-$code", "refresh-$code", 1800)
        },
        revoke = { url, token -> revoked += url to token },
        checkMa = { url, token ->
            maChecked += url to token
            maAnswer
        },
        maGuess = { "http://192.168.1.5:8095" },
        random = SecureRandom(),
        now = { now },
        wrongCodeDelayMs = 0L,
    )

    private fun post(path: String, body: String) = runBlocking {
        handler.handle(MiniHttp.Request("POST", path, emptyMap(), emptyMap(), body.toByteArray()))
    }

    private fun json(r: MiniHttp.Response): JsonObject = Json.parseToJsonElement(String(r.body)).jsonObject

    @Test
    fun `serves the page with the prefilled code`() = runBlocking {
        val r = handler.handle(MiniHttp.Request("GET", "/", mapOf("code" to "123456"), emptyMap(), ByteArray(0)))
        assertThat(r.status).isEqualTo(200)
        val html = String(r.body)
        assertThat(html).contains("value=\"123456\"")
        assertThat(html).contains("Langlebiges Zugangstoken")
        assertThat(html).contains("<html lang=\"de\">")
        val bad = handler.handle(MiniHttp.Request("GET", "/", mapOf("code" to "<script>"), emptyMap(), ByteArray(0)))
        assertThat(String(bad.body)).doesNotContain("<script>\"")
    }

    @Test
    fun `api calls need the code and never return the token`() {
        assertThat(post("/api/state", """{"code":"000000x"}""").status).isEqualTo(403)
        val r = post("/api/state", """{"code":"${handler.code}"}""")
        assertThat(r.status).isEqualTo(200)
        val body = String(r.body)
        assertThat(body).doesNotContain("secret-token")
        assertThat(json(r)["auth"]!!.jsonPrimitive.content).isEqualTo("token")
    }

    @Test
    fun `too many wrong codes rotate the code`() {
        val first = handler.code
        repeat(SetupHandler.MAX_FAILURES) { post("/api/state", """{"code":"999999"}""") }
        // The chance that the new random code equals the old one is 1e-6.
        assertThat(handler.code).isNotEqualTo(first)
    }

    @Test
    fun `discover reuses the stored token for the same server`() {
        val r = post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}""")
        assertThat(json(r)["ok"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(discovered.single()).isEqualTo("http://192.168.1.5:8123" to HaCredentials.LongLived("secret-token"))
        val other = post("/api/discover", """{"code":"${handler.code}","url":"10.0.0.9:8123","token":""}""")
        assertThat(other.status).isEqualTo(400)
    }

    @Test
    fun `save validates and stores`() {
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
    fun `a stored token only goes to the address it was made for`() {
        val moved = post("/api/save", """{"code":"${handler.code}","url":"10.0.0.9:8123","token":"","entity":"media_player.wz"}""")
        assertThat(moved.status).isEqualTo(400)
        assertThat(stored.haUrl).isEqualTo("http://192.168.1.5:8123")
        val withToken = post("/api/save", """{"code":"${handler.code}","url":"10.0.0.9:8123","token":"new","entity":"media_player.wz"}""")
        assertThat(withToken.status).isEqualTo(200)
        assertThat(stored.haToken).isEqualTo("new")
    }

    @Test
    fun `music assistant is checked before it is stored, and leaving it empty removes it`() {
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
        assertThat(String(post("/api/state", """{"code":"${handler.code}"}""").body)).doesNotContain("ma-secret")

        // Saving again unchanged does not ask Music Assistant again; an empty address removes it.
        val checks = maChecked.size
        assertThat(post("/api/save", """{$base,"maUrl":"http://192.168.1.5:8095","maToken":""}""").status).isEqualTo(200)
        assertThat(maChecked).hasSize(checks)
        assertThat(post("/api/save", """{$base,"maUrl":""}""").status).isEqualTo(200)
        assertThat(stored.maUrl).isNull()
        assertThat(stored.maToken).isNull()
    }

    @Test
    fun `mini http parses requests and enforces limits`() {
        val raw = "POST /api/save?x=1&y=a%20b HTTP/1.1\r\nHost: tv\r\nContent-Length: 4\r\nContent-Type: application/json\r\n\r\n{\"a\"".toByteArray()
        val req = MiniHttp.read(ByteArrayInputStream(raw))
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/api/save")
        assertThat(req.query).containsExactly("x", "1", "y", "a b")
        assertThat(req.headers["content-type"]).isEqualTo("application/json")
        assertThat(req.bodyText).isEqualTo("{\"a\"")

        val tooBig = "POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray()
        assertThat(runCatching { MiniHttp.read(ByteArrayInputStream(tooBig)) }.exceptionOrNull())
            .isInstanceOf(MiniHttp.BadRequest::class.java)
        val chunked = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()
        assertThat(runCatching { MiniHttp.read(ByteArrayInputStream(chunked)) }.exceptionOrNull())
            .isInstanceOf(MiniHttp.BadRequest::class.java)

        val out = ByteArrayOutputStream()
        MiniHttp.write(out, MiniHttp.Response.json(200, "{}"))
        val text = out.toString()
        assertThat(text).startsWith("HTTP/1.1 200 OK\r\n")
        assertThat(text).contains("Content-Length: 2\r\n")
        assertThat(text).contains("Cache-Control: no-store")
    }

    @Test
    fun `speaker list prefers music assistant and hides displays`() {
        val r = SetupServer.parseSpeakers(
            """[
              {"entity_id":"light.x","state":"on","attributes":{}},
              {"entity_id":"media_player.kueche","state":"idle","attributes":{"friendly_name":"Küche"}},
              {"entity_id":"media_player.wz","state":"playing","attributes":{"friendly_name":"Wohnzimmer","mass_player_type":"player"}},
              {"entity_id":"media_player.tv_display","state":"idle","attributes":{"friendly_name":"TV","mass_player_type":"display","device_class":"tv"}}
            ]""",
            texts,
        ) as DiscoverResult.Ok
        assertThat(r.speakers.map { it.entityId }).containsExactly("media_player.wz", "media_player.kueche").inOrder()
        assertThat(r.speakers.first().state).isEqualTo("spielt")
    }

    @Test
    fun `the lights are offered, stored when the page showed them and kept when it did not`() {
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["lights"].toString()).contains("light.tv_left")
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        assertThat(post("/api/save", """{$base,"lightsLeft":"light.tv_left","lightsCenter":"","lightsRight":"light.tv_right"}""").status).isEqualTo(200)
        assertThat(stored.roomLights).isEqualTo(io.github.hazymorning.klangbild.config.RoomLights("light.tv_left", null, "light.tv_right"))
        val state = json(post("/api/state", """{"code":"${handler.code}"}"""))
        assertThat(state["lightsRight"]!!.jsonPrimitive.content).isEqualTo("light.tv_right")
        // Saved without the lights section (nothing found yet): the lights stay.
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.roomLights.ids).containsExactly("light.tv_left", "light.tv_right")
        // Only lights.
        assertThat(post("/api/save", """{$base,"lightsLeft":"switch.socket"}""").status).isEqualTo(400)
        assertThat(post("/api/save", """{$base,"lightsLeft":"","lightsCenter":"","lightsRight":""}""").status).isEqualTo(200)
        assertThat(stored.roomLights.isSet).isFalse()
    }

    @Test
    fun `the landscape's weather is offered and stored, automatic by default`() {
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["weather"].toString()).contains("weather.forecast_home")
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        assertThat(post("/api/save", """{$base,"weather":"weather.forecast_home"}""").status).isEqualTo(200)
        assertThat(stored.weatherEntity).isEqualTo("weather.forecast_home")
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["weather"]!!.jsonPrimitive.content).isEqualTo("weather.forecast_home")
        // Not sent: kept.
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.weatherEntity).isEqualTo("weather.forecast_home")
        assertThat(post("/api/save", """{$base,"weather":"none"}""").status).isEqualTo(200)
        assertThat(stored.weatherEntities).isEmpty()
        assertThat(post("/api/save", """{$base,"weather":""}""").status).isEqualTo(200)
        assertThat(stored.weatherEntities).isEqualTo(Settings.DEFAULT_WEATHER)
        assertThat(post("/api/save", """{$base,"weather":"sensor.temperatur"}""").status).isEqualTo(400)
        val r = SetupServer.parseSpeakers("""[{"entity_id":"weather.home","state":"partlycloudy","attributes":{"friendly_name":"Zuhause"}}]""", texts) as DiscoverResult.Ok
        assertThat(r.weather).containsExactly(WeatherOption("weather.home", "Zuhause", "teils bewölkt"))
    }

    @Test
    fun `the quick settings' own buttons are offered, stored when the page showed them and kept when it did not`() {
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["actions"].toString()).contains("script.ungroup_speakers")
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        val two = """[{"name":"Trennen","entity":"script.ungroup_speakers"},{"name":"","entity":"scene.movie"}]"""
        assertThat(post("/api/save", """{$base,"actions":$two}""").status).isEqualTo(200)
        assertThat(stored.quickActions).containsExactly(QuickAction("Trennen", "script.ungroup_speakers"), QuickAction("Movie", "scene.movie")).inOrder()
        val state = json(post("/api/state", """{"code":"${handler.code}"}"""))
        assertThat(state["actions"].toString()).isEqualTo("""[{"name":"Trennen","entity":"script.ungroup_speakers"},{"name":"Movie","entity":"scene.movie"}]""")
        // Saved without the section (nothing found yet): the buttons stay.
        assertThat(post("/api/save", """{$base}""").status).isEqualTo(200)
        assertThat(stored.quickActions).hasSize(2)
        // Nothing that is no button: a lock, a media player, an odd list.
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
        assertThat(r.actions.map { it.entityId }).containsExactly("script.a", "script.b", "scene.kino", "switch.socket").inOrder()
    }

    @Test
    fun `a button keeps its script's variables and its condition, and the page gets them back`() {
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
    fun `values for the status line are stored when the page showed them and kept when it did not`() {
        val base = """"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz""""
        val two = """[{"name":"Außen","entity":"sensor.outside"},{"name":"","entity":"weather.home","attribute":"temperature"}]"""
        assertThat(post("/api/save", """{$base,"values":$two}""").status).isEqualTo(200)
        assertThat(stored.ownValues).containsExactly(
            io.github.hazymorning.klangbild.domain.OwnValue("Außen", "sensor.outside"),
            io.github.hazymorning.klangbild.domain.OwnValue("", "weather.home", "temperature"),
        ).inOrder()
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
    fun `every entity is offered for values and conditions, without what only describes it`() {
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
        assertThat(wz.lists).containsExactly("group_members", 2)
        assertThat(wz.attributes.keys).containsExactly("volume_level", "media_title")
        assertThat(wz.attributes.getValue("media_title")).hasLength(40)
        // Sent to the page with the rest.
        val found = json(post("/api/discover", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":""}"""))
        assertThat(found["entities"].toString()).isEqualTo("[]")
    }

    @Test
    fun `a script's fields are read from Home Assistant's actions`() {
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
        assertThat(group.map { it.key }).containsExactly("player", "mode", "volume", "announce", "rooms", "delay").inOrder()
        assertThat(group[0].type).isEqualTo(ScriptField.ENTITY)
        assertThat(group[0].domains).containsExactly("media_player")
        assertThat(group[1].type).isEqualTo(ScriptField.SELECT)
        assertThat(group[1].options).containsExactly("join" to "join", "leave" to "Leave").inOrder()
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
    fun `every light is offered, whatever it can show`() {
        val r = SetupServer.parseSpeakers(
            """[
              {"entity_id":"light.tv_left","state":"off","attributes":{"friendly_name":"TV links","supported_color_modes":["color_temp","xy"]}},
              {"entity_id":"light.flur","state":"on","attributes":{"friendly_name":"Flur","supported_color_modes":["brightness"]}},
              {"entity_id":"light.band","state":"on","attributes":{"friendly_name":"Band","supported_color_modes":["rgbww"]}},
              {"entity_id":"switch.x","state":"on","attributes":{"supported_color_modes":["xy"]}}
            ]""",
            texts,
        ) as DiscoverResult.Ok
        assertThat(r.lights.map { it.entityId }).containsExactly("light.band", "light.flur", "light.tv_left").inOrder()
        assertThat(r.lights.last().state).isEqualTo("aus")
        // In English the states are English words.
        val en = SetupServer.parseSpeakers("""[{"entity_id":"light.flur","state":"on","attributes":{}}]""", TestTexts.english()) as DiscoverResult.Ok
        assertThat(en.lights.single().state).isEqualTo("on")
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()) = runBlocking {
        handler.handle(MiniHttp.Request("GET", path, query, emptyMap(), ByteArray(0)))
    }

    /** Starts a sign-in on the page; the address of Home Assistant's login page it gets back. */
    private fun startSignIn(url: String = "192.168.1.5:8123") =
        json(post("/api/signin", """{"code":"${handler.code}","url":"$url"}"""))["authorize"]!!.jsonPrimitive.content.toHttpUrl()

    @Test
    fun `signing in goes through Home Assistant's own login page and back`() {
        val authorize = startSignIn()
        assertThat(authorize.encodedPath).isEqualTo("/auth/authorize")
        assertThat(authorize.host).isEqualTo("192.168.1.5")
        assertThat(authorize.queryParameter("client_id")).isEqualTo(clientId)
        assertThat(authorize.queryParameter("redirect_uri")).isEqualTo("http://192.168.1.23:8765/auth/callback")
        val state = authorize.queryParameter("state")!!
        assertThat(state.length).isAtLeast(40)
        // Home Assistant sends the phone back with a code; the TV swaps it for tokens and the phone goes on.
        val back = get("/auth/callback", mapOf("code" to "abc", "state" to state))
        assertThat(back.status).isEqualTo(303)
        assertThat(back.headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_OK)
        assertThat(exchanged.single()).isEqualTo(Triple("http://192.168.1.5:8123", "abc", clientId))
        // The page learns it is signed in, never the tokens.
        val st = post("/api/state", """{"code":"${handler.code}"}""")
        assertThat(json(st)["auth"]!!.jsonPrimitive.content).isEqualTo("signin")
        assertThat(String(st.body)).doesNotContain("refresh-abc")
        // Saved with the form, the sign-in replaces the long-lived token.
        val saved = post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"","entity":"media_player.wz"}""")
        assertThat(saved.status).isEqualTo(200)
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.SignedIn("refresh-abc", clientId))
        assertThat(stored.haToken).isNull()
        assertThat(revoked).isEmpty()
    }

    @Test
    fun `the sign-in just saved is never revoked by the end of the setup that saving brings`() {
        val state = startSignIn().queryParameter("state")!!
        get("/auth/callback", mapOf("code" to "abc", "state" to state))
        // On the TV, saving closes the setup screen, and its reset runs before the answer is written.
        afterSave = { handler.reset() }
        val saved = post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"","entity":"media_player.wz"}""")
        assertThat(saved.status).isEqualTo(200)
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.SignedIn("refresh-abc", clientId))
        assertThat(revoked).isEmpty()
    }

    @Test
    fun `a callback without the right state, used twice or too late is refused`() {
        startSignIn()
        assertThat(get("/auth/callback", mapOf("code" to "abc", "state" to "guessed")).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        // The state of the one sign-in under way, once.
        val state = startSignIn().queryParameter("state")!!
        assertThat(get("/auth/callback", mapOf("code" to "abc", "state" to state)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_OK)
        assertThat(get("/auth/callback", mapOf("code" to "abc", "state" to state)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        // Ten minutes and more: Home Assistant's code has expired anyway.
        val late = startSignIn().queryParameter("state")!!
        now += SetupHandler.SIGN_IN_MS + 1
        assertThat(get("/auth/callback", mapOf("code" to "def", "state" to late)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        assertThat(exchanged.map { it.second }).containsExactly("abc")
        // A code Home Assistant refuses is a failed sign-in.
        exchangeFails = true
        val refused = startSignIn().queryParameter("state")!!
        assertThat(get("/auth/callback", mapOf("code" to "ghi", "state" to refused)).headers["Location"]).isEqualTo(SetupHandler.SIGN_IN_FAILED)
        // Starting a sign-in needs the code shown on the TV.
        assertThat(post("/api/signin", """{"code":"000000x","url":"192.168.1.5:8123"}""").status).isEqualTo(403)
    }

    @Test
    fun `a sign-in never saved is revoked, and so is one a new sign-in replaces`() = runBlocking {
        val first = startSignIn().queryParameter("state")!!
        get("/auth/callback", mapOf("code" to "one", "state" to first))
        val second = startSignIn().queryParameter("state")!!
        get("/auth/callback", mapOf("code" to "two", "state" to second))
        assertThat(revoked).containsExactly("http://192.168.1.5:8123" to "refresh-one")
        // The setup screen closes without saving.
        handler.reset()
        assertThat(revoked).containsExactly("http://192.168.1.5:8123" to "refresh-one", "http://192.168.1.5:8123" to "refresh-two").inOrder()
    }

    @Test
    fun `an earlier sign-in is revoked when the TV goes over to another one`() {
        stored = Settings(haUrl = "http://192.168.1.5:8123", haRefreshToken = "old-refresh", haClientId = clientId, entityId = "media_player.wz")
        assertThat(json(post("/api/state", """{"code":"${handler.code}"}"""))["auth"]!!.jsonPrimitive.content).isEqualTo("signin")
        // Saved again unchanged: the sign-in stays.
        assertThat(post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","entity":"media_player.wz"}""").status).isEqualTo(200)
        assertThat(revoked).isEmpty()
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.SignedIn("old-refresh", clientId))
        // A long-lived token instead: the sign-in is revoked.
        assertThat(post("/api/save", """{"code":"${handler.code}","url":"192.168.1.5:8123","token":"long","entity":"media_player.wz"}""").status).isEqualTo(200)
        assertThat(revoked).containsExactly("http://192.168.1.5:8123" to "old-refresh")
        assertThat(stored.haCredentials).isEqualTo(HaCredentials.LongLived("long"))
    }

    @Test
    fun `the page speaks the language the TV shows`() {
        texts = TestTexts.english()
        val html = String(get("/").body)
        assertThat(html).contains("<html lang=\"en\">")
        assertThat(html).contains("Long-lived access token")
        assertThat(html).doesNotContain("Zugangstoken")
        val r = post("/api/save", """{"code":"${handler.code}","url":"","entity":"x"}""")
        assertThat(json(r)["message"]!!.jsonPrimitive.content).doesNotContain("Adresse")
    }

    @Test
    fun `the page fills every placeholder, and its script uses every word it gets`() {
        for (words in listOf(TestTexts.german, TestTexts.english())) {
            texts = words
            val html = String(get("/").body)
            assertThat(Regex("""\{\{\w+}}|__[A-Z_]+__""").find(html)?.value).isNull()
            val given = Json.parseToJsonElement(Regex("""const T = (\{.*?});""").find(html)!!.groupValues[1]).jsonObject.keys
            val used = Regex("""\bT\.(\w+)""").findAll(html).map { it.groupValues[1] }.toSet()
            assertThat(given).containsExactlyElementsIn(used)
        }
    }

    @Test
    fun `qr code encodes the url`() {
        val m = SetupServer.qrMatrix("http://192.168.1.23:8765/?code=123456")
        assertThat(m.size).isAtLeast(21)
        assertThat(m.all { it.size == m.size }).isTrue()
        // finder pattern corner is dark
        assertThat(m[0][0]).isTrue()
    }
}
