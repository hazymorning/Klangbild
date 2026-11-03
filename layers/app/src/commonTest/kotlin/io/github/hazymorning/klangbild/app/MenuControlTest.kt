package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.ReleaseNotes
import io.github.hazymorning.klangbild.domain.UpdateInfo
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

/**
 * The settings menu on the TV: it opens on its groups, OK or right opens one, back goes up a
 * level. A step moves only the cursor (a held key makes twenty a second), and while up or down
 * is held the explanation waits until the key is let go.
 */
class MenuControlTest {
    private var recomputed = 0
    private var shown = 0
    private var setup = 0
    private var applied = 0
    private var updates = 0
    private var settings = Settings()

    private val host = object : ControllerHost {
        override val lock = SynchronizedObject()
        override val log: Log = QuietLog
        override val scope = CoroutineScope(EmptyCoroutineContext)
        override val now: () -> Long = { 0L }
        override val settings get() = this@MenuControlTest.settings
        override val ui = UiState(
            screen = Screen.Stage(TrackInfo("Song", listOf("A")), PlayerStatus.PLAYING, null),
            source = SourceState(LinkState.CONNECTED),
        )
        override val source: NowPlayingSource? = null
        override val texts: Texts = TestTexts.english()
        override var language: AppLanguage = AppLanguage.ENGLISH
        override val speaker = io.github.hazymorning.klangbild.domain.SpeakerLink.OFF
        override val update = UpdateInfo(name = "2026.10.5", latest = "v2026.11.0", notes = NOTES)
        override fun update() {
            updates++
        }
        override fun recompute() {
            recomputed++
        }
        override fun showMenu() {
            shown++
        }
        override fun openMenu() = Unit
        override fun applySettings(updated: Settings) {
            applied++
            this@MenuControlTest.settings = updated
        }
        override fun send(command: RemoteCommand) = Unit
        override fun requestSetup(show: Boolean) {
            setup++
        }
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

    @Test
    fun `the menu opens on its groups, OK opens one, back goes up a level and then out`() {
        menu.open()
        assertThat(state().section).isNull()
        assertThat(state().rows.map { it.label }).containsExactly("Picture", "Information", "Lights", "Remote", "Timer", "System")
        // Left on a group does nothing; right or OK opens it on its first line.
        menu.move(+1)
        menu.change(-1, wrap = false)
        assertThat(state().section).isNull()
        menu.change(+1, wrap = true)
        assertThat(state().section).isEqualTo(MenuSection.INFO)
        assertThat(state().cursor).isEqualTo(0)
        assertThat(state().rows.first().label).isEqualTo("Clock")
        // The list stops at the group's last line.
        repeat(20) { menu.move(+1) }
        assertThat(state().rows[state().cursor].label).isEqualTo("Lyrics")
        // Back: the groups again, on the group just left; back again closes the menu.
        assertThat(menu.back()).isTrue()
        assertThat(state().section).isNull()
        assertThat(state().cursor).isEqualTo(1)
        assertThat(menu.back()).isFalse()
        assertThat(menu.isOpen).isFalse()
        // Opened again, it starts on the groups.
        menu.open()
        assertThat(state().section).isNull()
        assertThat(state().cursor).isEqualTo(0)
    }

    @Test
    fun `right held on a group opens it once and changes nothing in it until it is pressed again`() {
        menu.open()
        menu.change(+1, wrap = false)
        menu.change(+1, wrap = false, repeat = true)
        menu.change(+1, wrap = false, repeat = true)
        assertThat(state().section).isEqualTo(MenuSection.PICTURE)
        assertThat(state().cursor).isEqualTo(0)
        assertThat(applied).isEqualTo(0)
        // Pressed again it changes the line, and held it runs along the scale.
        menu.change(+1, wrap = false)
        menu.change(+1, wrap = false, repeat = true)
        assertThat(applied).isEqualTo(2)
        // On the groups a held key opens none.
        menu.back()
        menu.move(+1)
        menu.change(+1, wrap = false, repeat = true)
        assertThat(state().section).isNull()
    }

    @Test
    fun `a key on a menu that has closed by itself does nothing`() {
        menu.open()
        menu.change(+1, wrap = true)
        repeat(4) { menu.move(+1) }
        menu.expire(MenuControl.IDLE_MS + 1)
        menu.change(+1, wrap = false)
        menu.move(+1)
        assertThat(menu.isOpen).isFalse()
        assertThat(applied).isEqualTo(0)
    }

    @Test
    fun `a setting that needs something set up first opens the setup`() {
        menu.open()
        menu.move(+1)
        menu.change(+1, wrap = true)
        repeat(20) { menu.move(+1) }
        // Lyrics without Music Assistant.
        menu.change(+1, wrap = true)
        assertThat(setup).isEqualTo(1)
        assertThat(menu.isOpen).isFalse()
    }

    @Test
    fun `Set up on phone, the first line of System, opens the setup`() {
        settings = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.living_room")
        menu.open()
        repeat(6) { menu.move(+1) }
        menu.change(+1, wrap = true)
        assertThat(state().rows[state().cursor].label).isEqualTo("Set up on phone")
        menu.change(+1, wrap = true)
        assertThat(setup).isEqualTo(1)
        assertThat(menu.isOpen).isFalse()
    }

    @Test
    fun `the system group tells of a new version, OK on its line shows the notes, OK on them installs it, and a switch below turns looking off`() {
        settings = Settings(updateCheck = true)
        menu.open()
        assertThat(state().rows.last().value).isEqualTo("New version")
        repeat(6) { menu.move(+1) }
        menu.change(+1, wrap = true)
        assertThat(state().section).isEqualTo(MenuSection.SYSTEM)
        repeat(7) { menu.move(+1) }
        assertThat(state().rows[state().cursor].label).isEqualTo("Update")
        assertThat(state().rows[state().cursor].value).isEqualTo("Version\u00A02026.11.0")
        // OK shows the new version's notes; a held OK, left and right change nothing there.
        menu.change(+1, wrap = true)
        menu.change(+1, wrap = true, repeat = true)
        menu.change(-1, wrap = false)
        assertThat(state().notes).isEqualTo(MenuNotes("2026.11.0", ReleaseNotes.points(NOTES)))
        assertThat(updates).isEqualTo(0)
        // Down goes through their points and stops at the last; Back goes to the Update line.
        repeat(5) { menu.move(+1) }
        assertThat(state().cursor).isEqualTo(1)
        menu.back()
        assertThat(state().notes).isNull()
        assertThat(state().rows[state().cursor].label).isEqualTo("Update")
        // OK on the notes installs it, once, and the Update line shows how it goes.
        menu.change(+1, wrap = true)
        menu.change(+1, wrap = true)
        menu.change(+1, wrap = true, repeat = true)
        assertThat(updates).isEqualTo(1)
        assertThat(state().notes).isNull()
        assertThat(state().rows[state().cursor].label).isEqualTo("Update")
        assertThat(settings.updateCheck).isTrue()
        // Check for updates turns looking off, and the notice goes.
        menu.move(+1)
        assertThat(state().rows[state().cursor].label).isEqualTo("Check for updates")
        assertThat(state().rows[state().cursor].value).isEqualTo("On")
        menu.change(-1, wrap = false)
        assertThat(settings.updateCheck).isFalse()
        assertThat(state().rows[state().cursor].value).isEqualTo("Off")
        menu.back()
        assertThat(state().rows.last().value).isEqualTo("")
        // Off, OK on Update still shows the notes and installs it; OK on the switch turns it on again.
        menu.change(+1, wrap = true)
        repeat(10) { menu.move(+1) }
        menu.move(-1)
        menu.change(+1, wrap = true)
        menu.change(+1, wrap = true)
        assertThat(updates).isEqualTo(2)
        menu.move(+1)
        menu.change(+1, wrap = true)
        assertThat(settings.updateCheck).isTrue()
        assertThat(applied).isEqualTo(2)
    }

    private companion object {
        const val NOTES = "### New\n- **Lyrics.** They end with the song.\n- Faster."
    }
}
