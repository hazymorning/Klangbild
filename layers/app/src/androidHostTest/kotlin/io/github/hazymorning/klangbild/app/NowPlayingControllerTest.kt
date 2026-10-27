package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.config.SettingsStore
import io.github.hazymorning.klangbild.config.ValueStore
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The settings the controller owns: read from the store once and written on every change, without
 * the credentials Home Assistant refused.
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

    private fun controller(values: ValueStore) = NowPlayingController(
        scope, http, http, SettingsStore(values, PlainBox), Localizer(English), { null }, { _, _ -> NoSetupPage }, QuietLog,
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
