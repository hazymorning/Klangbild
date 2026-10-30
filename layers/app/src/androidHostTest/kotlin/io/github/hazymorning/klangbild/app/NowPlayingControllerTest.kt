package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.config.SettingsStore
import io.github.hazymorning.klangbild.config.ValueStore
import io.github.hazymorning.klangbild.data.ha.HaDevice
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.LanguageStore
import io.github.hazymorning.klangbild.text.Locale
import io.github.hazymorning.klangbild.text.Localizer
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The settings the controller owns: read from the store once and written on every change, without
 * the credentials Home Assistant refused. And the screen that goes dark while no one is in the
 * room, and the notifications Home Assistant sends.
 */
class NowPlayingControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = httpClient(OkHttp.create())
    private val screen = ScreenSpec(960, 540, 1f)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a change before the stored settings are read leaves them alone`() = runBlocking {
        val kept = mapOf("entity_id" to "media_player.wz", "queue" to "LIST")
        val stored = MemoryValues(kept)
        // The store answers only once the quick settings are open.
        val opened = CompletableDeferred<Unit>()
        val slow = object : ValueStore by stored {
            override val data: Flow<Map<String, Any>> = flow {
                opened.await()
                emitAll(stored.data)
            }
        }
        val controller = controller(slow)
        controller.attach(screen)
        // Opening the quick settings marks them as seen, which changes the settings.
        controller.quick.open()
        opened.complete(Unit)
        withTimeout(5_000) { controller.ui.first { it.queue == QueueDisplay.LIST } }
        assertThat(stored.data.value).isEqualTo(kept)
        controller.detach()
    }

    @Test
    fun `credentials Home Assistant refuses are dropped and never sent again`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
            server.start()
            val stored = MemoryValues(
                mapOf(
                    "ha_url" to server.url("/").toString().trimEnd('/'),
                    "ha_refresh_token" to "revoked",
                    "ha_client_id" to "http://192.168.1.23:8765/",
                    "entity_id" to "media_player.wz",
                ),
            )
            val controller = controller(stored)
            controller.attach(screen)
            withTimeout(5_000) { stored.data.first { "ha_refresh_token" !in it } }
            assertThat(stored.data.value["ha_refused"]).isEqualTo(true)
            // Shown again, the screen makes a new connection, which would send stored credentials again.
            controller.detach()
            controller.attach(screen)
            withTimeout(5_000) { controller.ui.first { it.screen == Screen.Setup(SetupReason.AUTH_FAILED) } }
            assertThat(server.requestCount).isEqualTo(1)
            controller.detach()
        }
    }

    @Test
    fun `the screen goes dark while no one is in the room, a key brings it back, and a long while lets it sleep`() = runBlocking {
        MockWebServer().use { ha ->
            ha.dispatcher = QuietRoom()
            ha.start()
            val stored = MemoryValues(
                mapOf(
                    "ha_url" to ha.url("/").toString().trimEnd('/'),
                    "ha_token" to "good",
                    "entity_id" to "media_player.wz",
                    "presence_sensors" to "binary_sensor.couch",
                ),
            )
            var clock = 1_800_000_000_000L
            val controller = controller(stored, now = { clock })
            controller.attach(screen)
            // The sensor has seen no one since it was first read; a minute later the screen goes dark.
            withTimeout(5_000) { controller.ui.first { it.source.entities["binary_sensor.couch"]?.state == "off" } }
            assertThat(controller.ui.value.dark).isFalse()
            clock += 61_000
            withTimeout(5_000) { controller.ui.first { it.dark } }
            // A key only brings the picture back, and keeps it for another minute.
            assertThat(controller.keyPressed()).isTrue()
            assertThat(controller.ui.value.dark).isFalse()
            assertThat(controller.keyPressed()).isFalse()
            clock += 61_000
            withTimeout(5_000) { controller.ui.first { it.dark } }
            assertThat(controller.ui.value.asleep).isFalse()
            // No one for as long as "Turn off after": the screen may sleep.
            clock += 10 * 60_000L
            withTimeout(5_000) { controller.ui.first { it.asleep } }
            controller.detach()
        }
    }

    @Test
    fun `with notifications on, this TV registers once and shows what Home Assistant sends`() = runBlocking {
        MockWebServer().use { ha ->
            val room = QuietRoom()
            ha.dispatcher = room
            ha.start()
            val stored = MemoryValues(
                mapOf(
                    "ha_url" to ha.url("/").toString().trimEnd('/'),
                    "ha_token" to "good",
                    "entity_id" to "media_player.wz",
                    "notifications" to true,
                ),
            )
            val controller = controller(stored, device = HaDevice("Wohnzimmer", "Box", "Acme", "Android", "12", "2026.11.0"))
            controller.attach(screen)
            withTimeout(5_000) { stored.data.first { it["notify_webhook"] == "hook" } }
            assertThat(stored.data.value["notify_device_id"] as String).hasLength(16)
            val shown = withTimeout(5_000) { controller.ui.first { it.message != null } }
            assertThat(shown.message).isEqualTo("Laundry${TimeText.SEPARATOR}Done")
            assertThat(room.registrations.get()).isEqualTo(1)
            controller.detach()
        }
    }

    /**
     * A Home Assistant whose speaker plays in a room where the presence sensor sees no one. It
     * registers this TV for notifications and sends one as soon as their channel opens.
     */
    private class QuietRoom : Dispatcher() {
        val registrations = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
            "/api/websocket" -> MockResponse.Builder().webSocketUpgrade(Socket()).build()
            "/api/mobile_app/registrations" -> {
                registrations.incrementAndGet()
                MockResponse.Builder().code(201).body("""{"cloudhook_url":null,"remote_ui_url":null,"secret":null,"webhook_id":"hook"}""").build()
            }
            "/api/states/media_player.wz" -> state("media_player.wz", "playing", """{"friendly_name":"Wohnzimmer","media_title":"Lied"}""")
            "/api/states/binary_sensor.couch" -> state("binary_sensor.couch", "off", """{"device_class":"presence"}""")
            else -> MockResponse.Builder().code(404).body("""{"message":"Entity not found."}""").build()
        }

        private fun state(id: String, state: String, attributes: String) = MockResponse.Builder().body(
            """{"entity_id":"$id","state":"$state","attributes":$attributes,"last_changed":"2026-10-10T18:00:00+00:00",
                "last_updated":"2026-10-10T18:00:00+00:00","context":{"id":"x","parent_id":null,"user_id":null}}""",
        ).build()

        /** Signs in and takes every command, reporting nothing on its own but a notification. */
        private class Socket : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Json.parseToJsonElement(text).jsonObject
                val id = msg["id"]?.jsonPrimitive?.content
                when (msg["type"]?.jsonPrimitive?.content) {
                    "auth" -> webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                    "ping" -> webSocket.send("""{"id":$id,"type":"pong"}""")
                    "config/entity_registry/list_for_display" -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":{"entity_categories":{},"entities":[]}}""")
                    "mobile_app/push_notification_channel" -> {
                        webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                        webSocket.send("""{"id":$id,"type":"event","event":{"message":"Done","title":"Laundry"}}""")
                    }
                    else -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
    }

    private fun controller(values: ValueStore, now: () -> Long = { System.currentTimeMillis() }, device: HaDevice? = null) = NowPlayingController(
        scope, http, http, SettingsStore(values, PlainBox), Localizer(English), { null }, { _, _ -> NoSetupPage }, QuietLog, now, device = device,
    )

    /** Keeps secrets as they are. */
    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain

        override fun open(stored: String) = stored

        override fun isPlain(stored: String) = false
    }

    private object English : LanguageStore {
        override fun get() = AppLanguage.ENGLISH

        override fun set(language: AppLanguage) = Unit

        override fun systemLocales() = listOf(Locale("en", "US"))

        override fun clock24(locales: List<Locale>) = false
    }

    /** A setup page that is never served. */
    private object NoSetupPage : SetupServer {
        override val info: StateFlow<SetupInfo?> = MutableStateFlow(null)
        override val origin: String? = null

        override fun start() = Unit

        override fun stop() = Unit
    }
}
