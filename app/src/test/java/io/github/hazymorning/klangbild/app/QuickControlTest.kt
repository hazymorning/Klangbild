package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.config.Condition
import io.github.hazymorning.klangbild.config.LyricsChoice
import io.github.hazymorning.klangbild.config.QuickAction
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.data.ha.EntityState
import io.github.hazymorning.klangbild.data.ma.MaLink
import io.github.hazymorning.klangbild.domain.Lrc
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.SongWords
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The quick settings' own buttons on the TV: OK does what a button says, once at a time, and the
 * row says how it went; a switch shows its new state at once; a button whose entity cannot be
 * used, or whose condition does not hold, is left out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickControlTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)

    private val ungroup = QuickAction("Ungroup", "script.ungroup_speakers")
    private val party = QuickAction("Party", "input_boolean.party")
    private val gone = QuickAction("Gone", "scene.gone")

    /** Runs what it is asked; answers when told to. */
    private inner class FakeSource : NowPlayingSource {
        override val state: StateFlow<SourceState> = MutableStateFlow(SourceState(LinkState.CONNECTED))
        val ran = mutableListOf<String>()
        val variables = mutableListOf<JsonObject>()
        var answer = CompletableDeferred<Boolean>()
        override fun start() = Unit
        override fun stop() = Unit
        override suspend fun send(command: RemoteCommand) = Unit
        override suspend fun seek(positionMs: Long) = Unit
        override suspend fun run(action: QuickAction): Boolean {
            ran += action.entity
            variables += action.variables
            return answer.await()
        }

        /** The link a party's guests open, as Music Assistant hands it out now; how often it was asked for. */
        var partyLink: String? = null
        var partyAsked = 0
        override suspend fun partyUrl(): String? {
            partyAsked++
            return partyLink
        }
    }

    private val source = FakeSource()
    private var ui = UiState(
        screen = Screen.Stage(TrackInfo("Song", listOf("A")), PlayerStatus.PLAYING, null),
        source = SourceState(LinkState.CONNECTED, entities = states(ungroup.entity to "off", party.entity to "off", gone.entity to "unavailable")),
    )

    /** Home Assistant's states for entities (`id to state`, attributes as JSON after a `|`). */
    private fun states(vararg s: Pair<String, String>): Map<String, EntityState> = s.associate { (id, v) ->
        val attrs = v.substringAfter('|', "{}")
        id to EntityState(id, v.substringBefore('|'), Json.parseToJsonElement(attrs).jsonObject, 0.0, 0.0)
    }

    private fun report(vararg s: Pair<String, String>) {
        ui = ui.copy(source = ui.source.copy(entities = ui.source.entities + states(*s)))
    }

    private var actions = listOf(ungroup, gone, party)

    /** The song's words in the text column, as the quick settings set them. */
    private var lyrics = false

    /** The quick settings have been opened once (the stage's hint ends). */
    private var quickSeen = false

    /** How often the landscape was left, and the settings menu opened. */
    private var landscapeLeft = 0
    private var menuOpened = 0

    private val host = object : ControllerHost {
        override val lock: Any = this
        override val scope = test
        override val now: () -> Long = { test.testScheduler.currentTime }
        override val settings get() = Settings(quickActions = actions, lyrics = lyrics, quickSeen = quickSeen)
        override val ui: UiState get() = this@QuickControlTest.ui
        override val source: NowPlayingSource = this@QuickControlTest.source
        override val texts: Texts = TestTexts.english()
        override var language: AppLanguage = AppLanguage.ENGLISH
        override fun recompute() = Unit
        override fun showMenu() = Unit
        override fun openMenu() {
            menuOpened++
        }
        override fun applySettings(updated: Settings) {
            lyrics = updated.lyrics
            quickSeen = updated.quickSeen
        }
        override fun send(command: RemoteCommand) = Unit
        override fun requestSetup(show: Boolean) = Unit
        override fun showLandscape() = Unit
        override fun hideLandscape(): Boolean {
            landscapeLeft++
            return false
        }
    }

    private val quick = QuickControl(host)

    private fun rows() = quick.state(ui.screen, landscape = false)!!.rows

    private fun value(entity: String) = rows().first { it.item == QuickItem.ACTION && host.settings.quickActions[it.key.action].entity == entity }.value

    /** Opens the quick settings and goes right to the button for [entity]. */
    private fun choose(entity: String) {
        if (!quick.isOpen) quick.open(menuOpen = false)
        repeat(rows().size) {
            val s = quick.state(ui.screen, landscape = false)!!
            val row = s.rows[s.cursor]
            if (row.item == QuickItem.ACTION && host.settings.quickActions[row.key.action].entity == entity) return
            quick.move(+1)
        }
        error("no button for $entity")
    }

    @Test
    fun `a button runs once at a time, says how it went, then what it does again`() {
        choose(ungroup.entity)
        assertThat(value(ungroup.entity)).isEqualTo("Run")
        quick.change()
        quick.change()
        test.runCurrent()
        assertThat(source.ran).containsExactly(ungroup.entity)
        source.answer.complete(true)
        test.runCurrent()
        assertThat(value(ungroup.entity)).isEqualTo("Done")
        test.advanceTimeBy(QuickControl.RESULT_MS + 100)
        assertThat(value(ungroup.entity)).isEqualTo("Run")
        // Pressed again: it runs again.
        source.answer = CompletableDeferred(false)
        quick.change()
        test.runCurrent()
        assertThat(source.ran).hasSize(2)
        assertThat(value(ungroup.entity)).isEqualTo("Failed")
    }

    @Test
    fun `a switch shows its new state at once and keeps it until Home Assistant says so`() {
        choose(party.entity)
        assertThat(value(party.entity)).isEqualTo("Off")
        quick.change()
        assertThat(value(party.entity)).isEqualTo("On")
        source.answer.complete(true)
        test.runCurrent()
        assertThat(value(party.entity)).isEqualTo("On")
        report(party.entity to "on")
        assertThat(value(party.entity)).isEqualTo("On")
        // Home Assistant could not switch it: it says so, then shows what Home Assistant reports.
        source.answer = CompletableDeferred(false)
        quick.change()
        assertThat(value(party.entity)).isEqualTo("Off")
        test.runCurrent()
        assertThat(value(party.entity)).isEqualTo("Failed")
        test.advanceTimeBy(QuickControl.RESULT_MS + 100)
        assertThat(value(party.entity)).isEqualTo("On")
    }

    @Test
    fun `a button whose entity cannot be used is left out, and comes when it can`() {
        quick.open(menuOpen = false)
        val shown = rows().filter { it.item == QuickItem.ACTION }.map { it.label }
        assertThat(shown).containsExactly("Ungroup", "Party").inOrder()
        report(gone.entity to "2026-10-10T20:00:00+00:00")
        assertThat(rows().filter { it.item == QuickItem.ACTION }.map { it.label }).containsExactly("Ungroup", "Gone", "Party").inOrder()
        ui = ui.copy(source = ui.source.copy(entities = emptyMap()))
        assertThat(rows().none { it.item == QuickItem.ACTION }).isTrue()
    }

    @Test
    fun `a button with a condition shows only while it holds, and says how it went before it goes`() {
        // Ungroup only while the speaker plays in a group (it counts itself among the members).
        val grouped = Condition("media_player.wz", "group_members", Condition.Test.ABOVE, "1")
        actions = listOf(ungroup.copy(condition = grouped), party)
        report("media_player.wz" to """playing|{"group_members":["media_player.wz"]}""")
        quick.open(menuOpen = false)
        assertThat(rows().filter { it.item == QuickItem.ACTION }.map { it.label }).containsExactly("Party")
        report("media_player.wz" to """playing|{"group_members":["media_player.wz","media_player.kitchen"]}""")
        assertThat(rows().filter { it.item == QuickItem.ACTION }.map { it.label }).containsExactly("Ungroup", "Party").inOrder()
        // Pressed, it ungroups: the condition ends, but the button still says it is done.
        choose(ungroup.entity)
        quick.change()
        source.answer.complete(true)
        test.runCurrent()
        report("media_player.wz" to """playing|{"group_members":["media_player.wz"]}""")
        assertThat(value(ungroup.entity)).isEqualTo("Done")
        test.advanceTimeBy(QuickControl.RESULT_MS + 100)
        assertThat(rows().filter { it.item == QuickItem.ACTION }.map { it.label }).containsExactly("Party")
        // A condition on an entity Home Assistant does not have never holds.
        actions = listOf(ungroup.copy(condition = Condition("sensor.gone", null, Condition.Test.IS_NOT, "x")))
        assertThat(rows().none { it.item == QuickItem.ACTION }).isTrue()
    }

    @Test
    fun `the lyrics entry shows with Music Assistant and a song, says whether this one has words, and OK turns it on and off`() {
        val song = TrackInfo("Song", listOf("A"), durationMs = 200_000L)
        ui = ui.copy(screen = Screen.Stage(song, PlayerStatus.PLAYING, null))
        quick.open(menuOpen = false)
        // Without Music Assistant's own link, no words can come: no entry.
        assertThat(rows().map { it.item }).doesNotContain(QuickItem.LYRICS)
        ui = ui.copy(source = ui.source.copy(ma = MaLink.CONNECTED))
        fun lyricsValue() = rows().first { it.item == QuickItem.LYRICS }.value
        assertThat(rows().map { it.item }.take(2)).containsExactly(QuickItem.PICTURE, QuickItem.LYRICS).inOrder()
        assertThat(lyricsValue()).isEqualTo("Off")
        quick.move(+1)
        quick.change()
        assertThat(lyrics).isTrue()
        // Not known yet: on. Known to have none: none. With words: on.
        assertThat(lyricsValue()).isEqualTo("On")
        ui = ui.copy(source = ui.source.copy(words = SongWords(song.key, null)))
        assertThat(lyricsValue()).isEqualTo("None")
        ui = ui.copy(source = ui.source.copy(words = SongWords(song.key, Lrc.parse("[00:01.00]Eins\n[00:05.00]Zwei"))))
        assertThat(lyricsValue()).isEqualTo("On")
        quick.change()
        assertThat(lyrics).isFalse()
        assertThat(lyricsValue()).isEqualTo("Off")
        // A station's song: no entry.
        ui = ui.copy(screen = Screen.Stage(song.copy(kind = MediaKind.RADIO), PlayerStatus.PLAYING, null))
        assertThat(rows().map { it.item }).doesNotContain(QuickItem.LYRICS)
        assertThat(LyricsChoice.entries).hasSize(3)
    }

    @Test
    fun `two buttons for one script are two buttons`() {
        val join = QuickAction("Join", "script.group", Json.parseToJsonElement("""{"mode":"join"}""").jsonObject)
        val leave = QuickAction("Leave", "script.group", Json.parseToJsonElement("""{"mode":"leave"}""").jsonObject)
        actions = listOf(join, leave)
        report("script.group" to "off")
        choose(join.entity)
        quick.change()
        test.runCurrent()
        // The one is running; the other can still be pressed.
        quick.move(+1)
        quick.change()
        test.runCurrent()
        assertThat(source.ran).containsExactly("script.group", "script.group")
        assertThat(source.variables).containsExactly(join.variables, leave.variables).inOrder()
    }

    /** Opens the quick settings and goes right to [item]. */
    private fun chooseEntry(item: QuickItem) {
        if (!quick.isOpen) quick.open(menuOpen = false)
        repeat(rows().size) {
            val s = quick.state(ui.screen, landscape = false)!!
            if (s.rows[s.cursor].item == item) return
            quick.move(+1)
        }
        error("no entry $item")
    }

    private fun partyValue() = rows().first { it.item == QuickItem.PARTY }.value

    private fun partyHere(here: Boolean) {
        ui = ui.copy(source = ui.source.copy(party = here))
    }

    @Test
    fun `the party code shows once switched on, and goes for good when the party ends`() {
        source.partyLink = "http://192.168.1.5:8095/?join=Q4WZ8TNP"
        partyHere(true)
        chooseEntry(QuickItem.PARTY)
        assertThat(partyValue()).isEqualTo("Off")
        quick.change()
        test.runCurrent()
        assertThat(partyValue()).isEqualTo("On")
        assertThat(quick.partyWanted(true)).isTrue()
        assertThat(quick.partyCode).isEqualTo("http://192.168.1.5:8095/?join=Q4WZ8TNP")
        // It shows at once: the landscape makes way.
        assertThat(landscapeLeft).isEqualTo(1)
        // The code expired: Music Assistant's new link comes with the next question.
        source.partyLink = "http://192.168.1.5:8095/?join=M2KD7RXA"
        test.advanceTimeBy(QuickControl.PARTY_URL_MS)
        test.runCurrent()
        assertThat(quick.partyCode).isEqualTo("http://192.168.1.5:8095/?join=M2KD7RXA")
        // The party ends: the code goes and nobody asks for its link any more.
        assertThat(quick.partyWanted(false)).isFalse()
        assertThat(quick.partyCode).isNull()
        partyHere(false)
        assertThat(rows().map { it.item }).doesNotContain(QuickItem.PARTY)
        val asked = source.partyAsked
        test.advanceTimeBy(5 * QuickControl.PARTY_URL_MS)
        test.runCurrent()
        assertThat(source.partyAsked).isEqualTo(asked)
        // The next party starts without it.
        partyHere(true)
        assertThat(partyValue()).isEqualTo("Off")
        assertThat(quick.partyWanted(true)).isFalse()
    }

    @Test
    fun `a party link that could not be had is asked for again soon, and one once had stays`() {
        partyHere(true)
        chooseEntry(QuickItem.PARTY)
        quick.change()
        test.runCurrent()
        assertThat(quick.partyWanted(true)).isTrue()
        assertThat(quick.partyCode).isNull()
        source.partyLink = "http://192.168.1.5:8095/?join=Q4WZ8TNP"
        test.advanceTimeBy(QuickControl.PARTY_RETRY_MS)
        test.runCurrent()
        assertThat(quick.partyCode).isEqualTo("http://192.168.1.5:8095/?join=Q4WZ8TNP")
        // No answer later on: the code stays as it was.
        source.partyLink = null
        test.advanceTimeBy(QuickControl.PARTY_URL_MS)
        test.runCurrent()
        assertThat(quick.partyCode).isEqualTo("http://192.168.1.5:8095/?join=Q4WZ8TNP")
        // Off: no code, no more questions.
        quick.change()
        assertThat(partyValue()).isEqualTo("Off")
        assertThat(quick.partyCode).isNull()
        val asked = source.partyAsked
        test.advanceTimeBy(5 * QuickControl.PARTY_URL_MS)
        test.runCurrent()
        assertThat(source.partyAsked).isEqualTo(asked)
    }

    @Test
    fun `the last entry opens all settings, and the quick settings make way`() {
        if (!quick.isOpen) quick.open(menuOpen = false)
        assertThat(rows().last().item).isEqualTo(QuickItem.SETTINGS)
        assertThat(rows().last().label).isEqualTo("Settings")
        assertThat(rows().last().value).isEqualTo("Open")
        chooseEntry(QuickItem.SETTINGS)
        quick.change()
        assertThat(quick.isOpen).isFalse()
        assertThat(menuOpened).isEqualTo(1)
    }

    @Test
    fun `once opened, the quick settings are known and the stage no longer tells how`() {
        assertThat(quickSeen).isFalse()
        quick.open(menuOpen = false)
        assertThat(quickSeen).isTrue()
    }
}
