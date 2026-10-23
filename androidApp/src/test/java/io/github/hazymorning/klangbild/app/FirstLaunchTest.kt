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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The very first start, with the connection in its extras (as Home Assistant or the CI sends
 * it): the app takes it at once, connects and stays connected; it never falls back to the
 * setup page. With `-Pklangbild.fakeHa=http://127.0.0.1:8123` (ci/fake_ha.py running) it talks
 * to the stand-in Home Assistant for a few minutes. It runs on the oldest Android Klangbild
 * supports and on a recent one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 34], qualifiers = "w960dp-h540dp-land-xhdpi")
class FirstLaunchTest {
    @Test
    fun theFirstStartTakesTheConnectionFromItsExtrasAndKeepsIt() {
        val ha = System.getProperty("klangbild.fakeHa")
        val app = ApplicationProvider.getApplicationContext<KlangbildApp>()
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("ha_url", ha ?: "http://127.0.0.1:9")
            .putExtra("token", "smoke")
            .putExtra("entity", "media_player.living_room")
            .putExtra("landscape", "on")
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            watch(app, ha)
        } finally {
            // Closed at the end, so nothing of it keeps running into the next test.
            activity.pause().stop().destroy()
        }
    }

    private fun watch(app: KlangbildApp, ha: String?) {
        val controller = app.graph.controller
        val seconds = if (ha != null) 200 else 10
        val start = System.currentTimeMillis()
        var connected = false
        var last: String? = null
        while (System.currentTimeMillis() - start < seconds * 1_000L) {
            shadowOf(Looper.getMainLooper()).idle()
            val ui = controller.ui.value
            val now = "${ui.source.link} ${ui.screen::class.simpleName}${(ui.screen as? Screen.Setup)?.reason ?: ""}"
            if (now != last) {
                println("FirstLaunch ${(System.currentTimeMillis() - start) / 1000.0} s: $now")
                last = now
            }
            if (ui.source.link != LinkState.UNCONFIGURED && ui.source.link != LinkState.CONNECTING) connected = true
            if (connected || ha == null) {
                assertThat(ui.source.link, name = "link after ${(System.currentTimeMillis() - start) / 1000} s").isNotEqualTo(LinkState.UNCONFIGURED)
            }
            if (ha == null && ui.source.link != LinkState.UNCONFIGURED) return
            Thread.sleep(50)
        }
        if (ha == null) assertThat(controller.ui.value.source.link, name = "link after the first start").isNotEqualTo(LinkState.UNCONFIGURED)
    }
}
