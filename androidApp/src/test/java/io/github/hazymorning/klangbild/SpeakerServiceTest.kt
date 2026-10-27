package io.github.hazymorning.klangbild

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.config.Settings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The speaker's service starts only while the setting is on and Music Assistant is set up,
 * while Klangbild shows, and ends itself once the setting goes off. With the speaker off
 * nothing of it runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
class SpeakerServiceTest {
    private val app = ApplicationProvider.getApplicationContext<KlangbildApp>()

    /** Klangbild opened with Home Assistant and Music Assistant set up, and [extras]. */
    private fun launch(vararg extras: Pair<String, String>) = Robolectric.buildActivity(
        MainActivity::class.java,
        Intent(app, MainActivity::class.java).apply {
            putExtra("ha_url", "http://127.0.0.1:9")
            putExtra("token", "test")
            putExtra("entity", "media_player.living_room")
            putExtra("ma_url", "http://127.0.0.1:9")
            putExtra("ma_token", "test")
            for ((k, v) in extras) putExtra(k, v)
        },
    ).setup()

    /** The stored settings, once they meet [condition]. */
    private fun stored(condition: (Settings) -> Boolean): Settings = runBlocking {
        withTimeout(WAIT_MS) { app.graph.settings.settings.first(condition) }
    }

    /** Runs the main thread's work until [done] holds. */
    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_MS * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            if (System.nanoTime() > deadline) throw AssertionError("$what: not within ${WAIT_MS / 1_000} s")
            Thread.sleep(10)
        }
    }

    @After
    fun stopApp() = app.graph.scope.cancel()

    @Test
    fun `with the speaker off nothing of it starts`() {
        val activity = launch()
        try {
            val settings = stored { it.isConfigured }
            // The activity has seen the stored settings.
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(settings.speakerOn).isFalse()
            assertThat(shadowOf(app).nextStartedService).isNull()
            assertThat(app.graph.speakerHub.speaker).isNull()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `switched on, the service starts and plays its part until switched off`() {
        val activity = launch("tv_speaker" to "on")
        var service: ServiceController<SpeakerService>? = null
        try {
            var started: Intent? = null
            until("the service's start") { shadowOf(app).nextStartedService?.also { started = it } != null }
            assertThat(started?.component?.className).isEqualTo(SpeakerService::class.java.name)
            service = Robolectric.buildService(SpeakerService::class.java, started).create().startCommand(0, 1)
            val shadow = shadowOf(service.get())
            until("the speaker") { app.graph.speakerHub.speaker != null }
            assertThat(shadow.lastForegroundNotification).isNotNull()

            // Switched off (here by an extra, as Home Assistant sends it): the speaker goes and the service ends itself.
            activity.newIntent(Intent(app, MainActivity::class.java).putExtra("tv_speaker", "off"))
            until("the service's end") { shadow.isStoppedBySelf }
            assertThat(app.graph.speakerHub.speaker).isNull()
        } finally {
            service?.destroy()
            activity.pause().stop().destroy()
        }
    }

    private companion object {
        /** How long a step may take on a machine that runs every module's tests at once. */
        const val WAIT_MS = 30_000L
    }
}
