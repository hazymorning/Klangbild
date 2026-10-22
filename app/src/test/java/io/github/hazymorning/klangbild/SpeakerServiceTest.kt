package io.github.hazymorning.klangbild

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The speaker's service starts only while the setting is on and Music Assistant is set up,
 * while Klangbild shows, and ends itself once the setting goes off. With the speaker off
 * nothing of it runs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
class SpeakerServiceTest {
    private val app = ApplicationProvider.getApplicationContext<KlangbildApp>()

    private fun launch(vararg extras: Pair<String, String>) = Robolectric.buildActivity(
        MainActivity::class.java,
        Intent(app, MainActivity::class.java).apply {
            putExtra("ha_url", "http://127.0.0.1:9")
            putExtra("token", "test")
            putExtra("entity", "media_player.living_room")
            for ((k, v) in extras) putExtra(k, v)
        },
    ).setup()

    /** Runs the main thread's work until [done] holds, or [ms] have passed. */
    private fun until(ms: Long = 5_000, done: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!done() && System.currentTimeMillis() - start < ms) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `with the speaker off nothing of it starts`() {
        val activity = launch()
        try {
            // A second for the settings to arrive and be acted on.
            until(1_000) { false }
            assertThat(shadowOf(app).nextStartedService).isNull()
            assertThat(app.graph.speakerHub.speaker).isNull()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `switched on, the service starts and plays its part until switched off`(): Unit = runBlocking {
        val activity = launch("ma_url" to "http://127.0.0.1:9", "ma_token" to "test", "tv_speaker" to "on")
        try {
            var started: Intent? = null
            until { shadowOf(app).nextStartedService?.also { started = it } != null }
            assertWithMessage("the service started").that(started?.component?.className).isEqualTo(SpeakerService::class.java.name)
            val service = Robolectric.buildService(SpeakerService::class.java, started).create().startCommand(0, 1)
            until { app.graph.speakerHub.speaker != null }
            assertThat(app.graph.speakerHub.speaker).isNotNull()
            assertThat(shadowOf(service.get()).lastForegroundNotification).isNotNull()

            // Switched off (in the menu, or by an extra): the speaker goes and the service ends itself.
            app.graph.settings.write(app.graph.settings.settings.first().copy(tvSpeaker = false))
            until { shadowOf(service.get()).isStoppedBySelf }
            assertThat(shadowOf(service.get()).isStoppedBySelf).isTrue()
            service.destroy()
            assertThat(app.graph.speakerHub.speaker).isNull()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
