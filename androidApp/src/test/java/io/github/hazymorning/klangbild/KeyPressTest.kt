package io.github.hazymorning.klangbild

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.dream.KlangbildDream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Klangbild takes a key whole, its press and its release, and so it does with the media keys it
 * has no use for: passed on, a media key's release would reach the media app that played last,
 * which may bring its own player to the front. A release whose press went elsewhere (the press
 * that ended the screensaver) is taken as well, and does nothing. The screensaver keeps a whole
 * press too, and ends once the key is let go.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class KeyPressTest {
    private val app = ApplicationProvider.getApplicationContext<KlangbildApp>()

    private fun launch() = Robolectric.buildActivity(
        MainActivity::class.java,
        Intent(app, MainActivity::class.java).apply {
            putExtra("ha_url", "http://127.0.0.1:9")
            putExtra("token", "test")
            putExtra("entity", "media_player.living_room")
        },
    ).setup()

    private fun down(code: Int, repeat: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat)

    private fun up(code: Int) = KeyEvent(KeyEvent.ACTION_UP, code)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the app takes every media key whole, and a release on its own`() {
        val activity = launch()
        try {
            val a = activity.get()
            for (code in MEDIA_KEYS) {
                val name = KeyEvent.keyCodeToString(code)
                assertThat(a.dispatchKeyEvent(down(code)), name = name).isTrue()
                assertThat(a.dispatchKeyEvent(up(code)), name = "$name let go").isTrue()
                assertThat(a.dispatchKeyEvent(up(code)), name = "$name let go, its press gone elsewhere").isTrue()
                idle()
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `a release whose press went elsewhere does nothing`() {
        val activity = launch()
        try {
            val a = activity.get()
            val controller = app.graph.controller
            a.dispatchKeyEvent(down(KeyEvent.KEYCODE_MENU))
            a.dispatchKeyEvent(up(KeyEvent.KEYCODE_MENU))
            idle()
            val menu = controller.ui.value.menu
            // OK let go in the settings changes the line chosen, but not without its press.
            assertThat(a.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_CENTER))).isTrue()
            idle()
            assertThat(controller.ui.value.menu, name = "after a release alone").isEqualTo(menu)
            a.dispatchKeyEvent(down(KeyEvent.KEYCODE_DPAD_CENTER))
            a.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_CENTER))
            idle()
            assertThat(controller.ui.value.menu, name = "after a whole press").isNotEqualTo(menu)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `the screensaver keeps the whole press and ends once the key is let go`() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) {
            val dream = Robolectric.buildService(KlangbildDream::class.java).create().get()
            val name = KeyEvent.keyCodeToString(code)
            assertThat(dream.dispatchKeyEvent(down(code)), name = name).isTrue()
            assertThat(dream.dispatchKeyEvent(down(code, repeat = 1)), name = "$name held").isTrue()
            assertThat(shadowOf(dream).isStoppedBySelf, name = "$name held").isFalse()
            assertThat(dream.dispatchKeyEvent(up(code)), name = "$name let go").isTrue()
            assertThat(shadowOf(dream).isStoppedBySelf, name = "$name let go").isTrue()
        }
    }

    private companion object {
        /** The keys Android hands to the media app that played last when the app in front does not take them. */
        val MEDIA_KEYS = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )
    }
}
