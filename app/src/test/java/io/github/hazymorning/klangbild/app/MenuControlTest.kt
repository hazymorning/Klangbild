package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The settings menu's cursor on the TV: a step moves only the cursor (a held key makes twenty a
 * second), and while up or down is held the explanation waits until the key is let go.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MenuControlTest {
    private var recomputed = 0
    private var shown = 0

    private val host = object : ControllerHost {
        override val lock: Any = this
        override val scope = CoroutineScope(EmptyCoroutineContext)
        override val now: () -> Long = { 0L }
        override val settings get() = Settings()
        override val ui = UiState(
            screen = Screen.Stage(TrackInfo("Song", listOf("A")), PlayerStatus.PLAYING, null),
            source = SourceState(LinkState.CONNECTED),
        )
        override val source: NowPlayingSource? = null
        override val texts: Texts = TestTexts.english()
        override var language: AppLanguage = AppLanguage.ENGLISH
        override fun recompute() {
            recomputed++
        }
        override fun showMenu() {
            shown++
        }
        override fun applySettings(updated: Settings) = Unit
        override fun send(command: RemoteCommand) = Unit
        override fun requestSetup(show: Boolean) = Unit
        override fun showVista() = Unit
        override fun hideVista() = false
    }

    private val menu = MenuControl(host, SleepControl(host))

    private fun state() = menu.moved(menu.state(host.settings, Appearance.DARK, null)!!)

    @Test
    fun `a held key moves only the cursor, and the explanation waits until it is let go`() {
        menu.open()
        menu.move(+1)
        assertThat(state().cursor).isEqualTo(1)
        assertThat(state().held).isFalse()
        menu.move(+1, held = true)
        menu.move(+1, held = true)
        assertThat(state().cursor).isEqualTo(3)
        assertThat(state().held).isTrue()
        // Opening worked the screen out; the steps only showed the cursor.
        assertThat(recomputed).isEqualTo(1)
        assertThat(shown).isEqualTo(3)
        menu.release()
        assertThat(state().held).isFalse()
        assertThat(shown).isEqualTo(4)
        // Nothing held: a key let go changes nothing.
        menu.release()
        assertThat(shown).isEqualTo(4)
    }

    @Test
    fun `a menu opened again has no key held`() {
        menu.open()
        menu.move(+1, held = true)
        menu.close()
        menu.open()
        assertThat(state().held).isFalse()
    }
}
