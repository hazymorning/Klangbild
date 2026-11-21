package io.github.hazymorning.klangbild.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.TestTexts
import kotlin.test.Test

class QuickMenuTest {
    private val de = TestTexts.german
    private val lights = Settings(roomLights = RoomLights(left = "light.left", center = null, right = null))

    @Test
    fun `shuffle stands where the sleep timer stood, only where the player can shuffle`() {
        val rows = QuickMenu.rows(lights, QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false), de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.VIEW, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS, QuickItem.SCREEN)
        assertThat(rows.first { it.item == QuickItem.SHUFFLE }.value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, QuickContext(music = true, shuffle = true), de).first { it.item == QuickItem.SHUFFLE }.value).isEqualTo("An")
        // A player that cannot shuffle, and no music: no entry.
        assertThat(QuickMenu.rows(lights, QuickContext(music = true, shuffle = null), de).map { it.item }).doesNotContain(QuickItem.SHUFFLE)
        assertThat(QuickMenu.rows(lights, QuickContext(music = false, shuffle = true), de).map { it.item }).doesNotContain(QuickItem.SHUFFLE)
    }

    @Test
    fun `the song's words stand after the view, only with Music Assistant and a song`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.ON)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.VIEW, QuickItem.LYRICS, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS, QuickItem.SCREEN)
        assertThat(rows[1].label).isEqualTo("Songtext")
        assertThat(rows[1].value).isEqualTo("An")
        assertThat(rows[1].muted).isFalse()
        val off = QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.OFF), de)[1]
        assertThat(off.value).isEqualTo("Aus")
        assertThat(off.muted).isFalse()
        // On, but this song has no synced words: still On, said quietly (a word of its own read
        // like the words switched off).
        val none = QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), de)[1]
        assertThat(none.value).isEqualTo("An")
        assertThat(none.muted).isTrue()
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), TestTexts.english())[1].value).isEqualTo("On")
        // No other entry is ever quiet.
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), de).filter { it.muted }.map { it.item }).containsExactlyInAnyOrder(QuickItem.LYRICS)
        // Its place is as wide as any of its values.
        assertThat(rows[1].values).containsExactlyInAnyOrder("An", "Aus")
        // Without Music Assistant, or without a song: no entry.
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = null), de).map { it.item }).doesNotContain(QuickItem.LYRICS)
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[1].label).isEqualTo("Lyrics")
    }

    @Test
    fun `the artist info stands after the song's words, only with Music Assistant and a song`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.ON, artist = ArtistChoice.ON)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(
            QuickItem.VIEW, QuickItem.LYRICS, QuickItem.ARTIST, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS, QuickItem.SCREEN,
        )
        assertThat(rows[2].label).isEqualTo("Künstlerinfo")
        assertThat(rows[2].value).isEqualTo("An")
        assertThat(QuickMenu.rows(lights, ctx.copy(artist = ArtistChoice.OFF), de)[2].value).isEqualTo("Aus")
        // On, but Music Assistant knows nothing about this artist: still On, said quietly.
        val none = QuickMenu.rows(lights, ctx.copy(artist = ArtistChoice.NONE), de)[2]
        assertThat(none.value).isEqualTo("An")
        assertThat(none.muted).isTrue()
        assertThat(rows[2].values).containsExactlyInAnyOrder("An", "Aus")
        assertThat(QuickMenu.rows(lights, ctx.copy(artist = null), de).map { it.item }).doesNotContain(QuickItem.ARTIST)
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[2].label).isEqualTo("Artist info")
    }

    @Test
    fun `the party code stands with what the screen shows, only while a party plays here`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.OFF, party = false)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(
            QuickItem.VIEW, QuickItem.LYRICS, QuickItem.PARTY, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.SETTINGS, QuickItem.SCREEN,
        )
        assertThat(rows[2].label).isEqualTo("Party-Code")
        assertThat(rows[2].value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, ctx.copy(party = true), de)[2].value).isEqualTo("An")
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[2].label).isEqualTo("Party code")
        // No party here, or no song on the stage to stand beside the code: no entry.
        assertThat(QuickMenu.rows(lights, ctx.copy(party = null), de).map { it.item }).doesNotContain(QuickItem.PARTY)
        assertThat(QuickMenu.rows(lights, ctx.copy(music = false), de).map { it.item }).doesNotContain(QuickItem.PARTY)
    }

    @Test
    fun `the view comes first, with every value it can show`() {
        val rows = QuickMenu.rows(lights, QuickContext(), de)
        assertThat(rows[0].label).isEqualTo("Ansicht")
        assertThat(rows[0].value).isEqualTo("Normal")
        assertThat(rows[0].values).containsExactly("Normal", "Randlos", "Cover Flow")
        assertThat(QuickMenu.rows(lights.copy(view = StageView.FLOW), QuickContext(), de)[0].value).isEqualTo("Cover Flow")
        // A choice still waiting is what it names.
        assertThat(QuickMenu.rows(lights, QuickContext(pending = StageView.EDGE), de)[0].value).isEqualTo("Randlos")
        assertThat(QuickMenu.rows(lights, QuickContext(), TestTexts.english())[0].label).isEqualTo("View")
    }

    @Test
    fun `the queue follows the view, only where a list can show`() {
        val ctx = QuickContext(list = true)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows[1].item).isEqualTo(QuickItem.QUEUE)
        assertThat(rows[1].label).isEqualTo("Warteschlange")
        assertThat(rows[1].value).isEqualTo("Aus")
        assertThat(rows[1].values).containsExactly("Aus", "Liste")
        assertThat(QuickMenu.rows(lights.copy(queue = QueueDisplay.LIST), ctx, de)[1].value).isEqualTo("Liste")
        // Without Music Assistant, and in Cover Flow (also while it's chosen and not applied yet), there's no list.
        assertThat(QuickMenu.rows(lights, QuickContext(), de).map { it.item }).doesNotContain(QuickItem.QUEUE)
        assertThat(QuickMenu.rows(lights.copy(view = StageView.FLOW), ctx, de).map { it.item }).doesNotContain(QuickItem.QUEUE)
        assertThat(QuickMenu.rows(lights, ctx.copy(pending = StageView.FLOW), de).map { it.item }).doesNotContain(QuickItem.QUEUE)
    }

    @Test
    fun `the view steps from the normal one to Cover Flow`() {
        fun step(v: StageView, direction: Int = 1, wrap: Boolean = true) = SettingsMenu.VIEW_ORDER.step(v, direction, wrap)
        assertThat(step(StageView.NORMAL)).isEqualTo(StageView.EDGE)
        assertThat(step(StageView.EDGE)).isEqualTo(StageView.FLOW)
        assertThat(step(StageView.FLOW)).isEqualTo(StageView.NORMAL)
        assertThat(step(StageView.NORMAL, direction = -1)).isEqualTo(StageView.FLOW)
        // Without going round, the ends hold.
        assertThat(step(StageView.FLOW, wrap = false)).isEqualTo(StageView.FLOW)
        assertThat(step(StageView.NORMAL, direction = -1, wrap = false)).isEqualTo(StageView.NORMAL)
    }

    @Test
    fun `the sleep timer lives in the settings, not in the quick settings`() {
        assertThat(QuickItem.entries.map { it.label }).doesNotContain(Strings.entrySleepTimer)
        assertThat(SettingsMenu.entries).contains(MenuEntry.SLEEP_TIMER)
    }

    @Test
    fun `the way into all settings comes last, after the buttons of one's own`() {
        val buttons = lights.copy(quickActions = listOf(QuickAction("Gruppe", "script.group")))
        val rows = QuickMenu.rows(buttons, QuickContext(actions = mapOf(0 to ActionState.READY)), de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.VIEW, QuickItem.LIGHTS, QuickItem.ACTION, QuickItem.SETTINGS)
        assertThat(rows.last().label).isEqualTo("Einstellungen")
        assertThat(rows.last().value).isEqualTo("Öffnen")
    }

    @Test
    fun `the screen comes last, only while music plays, and turns off`() {
        val rows = QuickMenu.rows(lights, QuickContext(music = true), de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.VIEW, QuickItem.LIGHTS, QuickItem.SETTINGS, QuickItem.SCREEN)
        assertThat(rows.last().label).isEqualTo("Bildschirm")
        assertThat(rows.last().value).isEqualTo("Ausschalten")
        assertThat(rows.last().values).containsExactly("Ausschalten")
        assertThat(QuickMenu.rows(lights, QuickContext(music = true), TestTexts.english()).last().let { it.label to it.value }).isEqualTo("Screen" to "Turn off")
        // Without music there is nothing to keep playing in the dark.
        assertThat(QuickMenu.rows(lights, QuickContext(music = false), de).map { it.item }).doesNotContain(QuickItem.SCREEN)
    }
}
