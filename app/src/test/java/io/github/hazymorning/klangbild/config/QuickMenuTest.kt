package io.github.hazymorning.klangbild.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.text.TestTexts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickMenuTest {
    private val de = TestTexts.german
    private val lights = Settings(roomLights = RoomLights(left = "light.left", center = null, right = null))

    @Test
    fun `shuffle stands where the sleep timer stood, only where the player can shuffle`() {
        val rows = QuickMenu.rows(lights, QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false), de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.QUEUE, QuickItem.LANDSCAPE, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS).inOrder()
        assertThat(rows.first { it.item == QuickItem.SHUFFLE }.value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, QuickContext(music = true, shuffle = true), de).first { it.item == QuickItem.SHUFFLE }.value).isEqualTo("An")
        // A player that cannot shuffle, and no music: no entry.
        assertThat(QuickMenu.rows(lights, QuickContext(music = true, shuffle = null), de).map { it.item }).doesNotContain(QuickItem.SHUFFLE)
        assertThat(QuickMenu.rows(lights, QuickContext(music = false, shuffle = true), de).map { it.item }).doesNotContain(QuickItem.SHUFFLE)
    }

    @Test
    fun `the song's words stand after the queue and the landscape, only with Music Assistant and a song`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.ON)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.QUEUE, QuickItem.LANDSCAPE, QuickItem.LYRICS, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS).inOrder()
        assertThat(rows[2].label).isEqualTo("Songtext")
        assertThat(rows[2].value).isEqualTo("An")
        assertThat(rows[2].muted).isFalse()
        val off = QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.OFF), de)[2]
        assertThat(off.value).isEqualTo("Aus")
        assertThat(off.muted).isFalse()
        // On, but this song has no synced words: still On, said quietly (a word of its own read
        // like the words switched off).
        val none = QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), de)[2]
        assertThat(none.value).isEqualTo("An")
        assertThat(none.muted).isTrue()
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), TestTexts.english())[2].value).isEqualTo("On")
        // No other entry is ever quiet.
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), de).filter { it.muted }.map { it.item }).containsExactly(QuickItem.LYRICS)
        // Its place is as wide as any of its values.
        assertThat(rows[2].values).containsExactly("An", "Aus")
        // Without Music Assistant, or without a song: no entry.
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = null), de).map { it.item }).doesNotContain(QuickItem.LYRICS)
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[2].label).isEqualTo("Lyrics")
    }

    @Test
    fun `the party code stands with what the screen shows, only while a party plays here`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.OFF, party = false)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(
            QuickItem.QUEUE, QuickItem.LANDSCAPE, QuickItem.LYRICS, QuickItem.PARTY, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS,
        ).inOrder()
        assertThat(rows[3].label).isEqualTo("Party-Code")
        assertThat(rows[3].value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, ctx.copy(party = true), de)[3].value).isEqualTo("An")
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[3].label).isEqualTo("Party code")
        // No party here, or no song on the stage to stand beside the code: no entry.
        assertThat(QuickMenu.rows(lights, ctx.copy(party = null), de).map { it.item }).doesNotContain(QuickItem.PARTY)
        assertThat(QuickMenu.rows(lights, ctx.copy(music = false), de).map { it.item }).doesNotContain(QuickItem.PARTY)
    }

    @Test
    fun `the queue comes first, the landscape after it, each with every value it can show`() {
        val rows = QuickMenu.rows(lights, QuickContext(landscape = true), de)
        assertThat(rows[0].label).isEqualTo("Warteschlange")
        assertThat(rows[0].value).isEqualTo("Aus")
        assertThat(rows[0].values).containsExactly("Aus", "Liste", "Cover Flow").inOrder()
        assertThat(QuickMenu.rows(lights.copy(queue = QueueDisplay.FLOW), QuickContext(), de)[0].value).isEqualTo("Cover Flow")
        // A choice still waiting is what it names.
        assertThat(QuickMenu.rows(lights, QuickContext(pending = QueueDisplay.LIST), de)[0].value).isEqualTo("Liste")
        assertThat(rows[1].label).isEqualTo("Landschaft")
        assertThat(rows[1].value).isEqualTo("An")
        assertThat(QuickMenu.rows(lights, QuickContext(landscape = false), de)[1].value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, QuickContext(), TestTexts.english()).take(2).map { it.label }).containsExactly("Queue", "Landscape").inOrder()
    }

    @Test
    fun `the queue steps from the quietest to the fullest, passing the list while it cannot show`() {
        fun step(q: QueueDisplay, list: Boolean, direction: Int = 1, wrap: Boolean = true) = QuickMenu.step(q, direction, wrap, list)
        assertThat(step(QueueDisplay.OFF, list = true)).isEqualTo(QueueDisplay.LIST)
        assertThat(step(QueueDisplay.LIST, list = true)).isEqualTo(QueueDisplay.FLOW)
        assertThat(step(QueueDisplay.FLOW, list = true)).isEqualTo(QueueDisplay.OFF)
        assertThat(step(QueueDisplay.OFF, list = false)).isEqualTo(QueueDisplay.FLOW)
        assertThat(step(QueueDisplay.FLOW, list = false)).isEqualTo(QueueDisplay.OFF)
        // Stood on while it cannot show, the list is left for the next one.
        assertThat(step(QueueDisplay.LIST, list = false)).isEqualTo(QueueDisplay.FLOW)
        assertThat(step(QueueDisplay.FLOW, list = false, direction = -1)).isEqualTo(QueueDisplay.OFF)
        // Without going round, the ends hold.
        assertThat(step(QueueDisplay.FLOW, list = true, wrap = false)).isEqualTo(QueueDisplay.FLOW)
        assertThat(step(QueueDisplay.OFF, list = true, direction = -1, wrap = false)).isEqualTo(QueueDisplay.OFF)
    }

    @Test
    fun `the sleep timer lives in the settings, not in the quick settings`() {
        assertThat(QuickItem.entries.map { it.label }).doesNotContain(R.string.entry_sleep_timer)
        assertThat(SettingsMenu.entries).contains(MenuEntry.SLEEP_TIMER)
    }

    @Test
    fun `the way into all settings comes last, after the buttons of one's own`() {
        val buttons = lights.copy(quickActions = listOf(QuickAction("Gruppe", "script.group")))
        val rows = QuickMenu.rows(buttons, QuickContext(actions = mapOf(0 to ActionState.READY)), de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.QUEUE, QuickItem.LANDSCAPE, QuickItem.LIGHTS, QuickItem.ACTION, QuickItem.SETTINGS).inOrder()
        assertThat(rows.last().label).isEqualTo("Einstellungen")
        assertThat(rows.last().value).isEqualTo("Öffnen")
    }
}
