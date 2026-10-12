package io.github.hazymorning.klangbild.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.R
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
        assertThat(rows.map { it.item }).containsExactly(QuickItem.PICTURE, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE).inOrder()
        assertThat(rows.last().value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, QuickContext(music = true, shuffle = true), de).last().value).isEqualTo("An")
        // A player that cannot shuffle, and no music: no entry.
        assertThat(QuickMenu.rows(lights, QuickContext(music = true, shuffle = null), de).map { it.item }).doesNotContain(QuickItem.SHUFFLE)
        assertThat(QuickMenu.rows(lights, QuickContext(music = false, shuffle = true), de).map { it.item }).doesNotContain(QuickItem.SHUFFLE)
    }

    @Test
    fun `the song's words stand after the view, only with Music Assistant and a song`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.ON)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.PICTURE, QuickItem.LYRICS, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE).inOrder()
        assertThat(rows[1].label).isEqualTo("Songtext")
        assertThat(rows[1].value).isEqualTo("An")
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.OFF), de)[1].value).isEqualTo("Aus")
        // On, but this song has no synced words.
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = LyricsChoice.NONE), de)[1].value).isEqualTo("Keiner")
        // Its place is as wide as any of its values.
        assertThat(rows[1].values).containsExactly("An", "Aus", "Keiner")
        // Without Music Assistant, or without a song: no entry.
        assertThat(QuickMenu.rows(lights, ctx.copy(lyrics = null), de).map { it.item }).doesNotContain(QuickItem.LYRICS)
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[1].label).isEqualTo("Lyrics")
    }

    @Test
    fun `the party code stands with what the screen shows, only while a party plays here`() {
        val ctx = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = LyricsChoice.OFF, party = false)
        val rows = QuickMenu.rows(lights, ctx, de)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.PICTURE, QuickItem.LYRICS, QuickItem.PARTY, QuickItem.FAVORITE, QuickItem.LIGHTS, QuickItem.SHUFFLE).inOrder()
        assertThat(rows[2].label).isEqualTo("Party-Code")
        assertThat(rows[2].value).isEqualTo("Aus")
        assertThat(QuickMenu.rows(lights, ctx.copy(party = true), de)[2].value).isEqualTo("An")
        assertThat(QuickMenu.rows(lights, ctx, TestTexts.english())[2].label).isEqualTo("Party code")
        // No party here, or no song on the stage to stand beside the code: no entry.
        assertThat(QuickMenu.rows(lights, ctx.copy(party = null), de).map { it.item }).doesNotContain(QuickItem.PARTY)
        assertThat(QuickMenu.rows(lights, ctx.copy(music = false), de).map { it.item }).doesNotContain(QuickItem.PARTY)
    }

    @Test
    fun `the sleep timer lives in the settings, not in the quick settings`() {
        assertThat(QuickItem.entries.map { it.label }).doesNotContain(R.string.entry_sleep_timer)
        assertThat(SettingsMenu.entries).contains(MenuEntry.SLEEP_TIMER)
    }
}
