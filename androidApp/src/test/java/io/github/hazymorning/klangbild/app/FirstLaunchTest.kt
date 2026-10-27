package io.github.hazymorning.klangbild.app

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isNotEqualTo
import io.github.hazymorning.klangbild.KlangbildApp
import io.github.hazymorning.klangbild.MainActivity
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.Screen
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The very first start, with the connection in its extras (as Home Assistant or the CI sends
 * it): the app takes it at once, connects and stays connected; it never falls back to the
 * setup page. With `-Pklangbild.fakeHa=http://127.0.0.1:8123` (ci/fake_ha.py running) it talks
 * to the stand-in Home Assistant for a few minutes. It runs on the oldest Android Klangbild
 * supports and on a recent one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
class FirstLaunchTest {
    private val app = ApplicationProvider.getApplicationContext<KlangbildApp>()

    @After
    fun stopApp() = app.graph.scope.cancel()

    @Test
    fun theFirstStartTakesTheConnectionFromItsExtrasAndKeepsIt() {
        val ha = System.getProperty("klangbild.fakeHa")
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("ha_url", ha ?: "http://127.0.0.1:9")
            .putExtra("token", "smoke")
            .putExtra("entity", "media_player.living_room")
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            runBlocking { withTimeout(30_000) { app.graph.settings.settings.first { it.isConfigured } } }
            watch(seconds = if (ha != null) 200 else 0)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    /** Follows the connection for [seconds] and prints each change; it never falls back to the setup page. */
    private fun watch(seconds: Int) {
        val controller = app.graph.controller
        val start = System.nanoTime()
        var last: String? = null
        do {
            shadowOf(Looper.getMainLooper()).idle()
            val ui = controller.ui.value
            val at = "${(System.nanoTime() - start) / 1_000_000 / 1000.0} s"
            val now = "${ui.source.link} ${ui.screen::class.simpleName}${(ui.screen as? Screen.Setup)?.reason ?: ""}"
            if (now != last) {
                println("FirstLaunch $at: $now")
                last = now
            }
            assertThat(ui.source.link, name = "link after $at").isNotEqualTo(LinkState.UNCONFIGURED)
            Thread.sleep(50)
        } while (System.nanoTime() - start < seconds * 1_000_000_000L)
    }
}
