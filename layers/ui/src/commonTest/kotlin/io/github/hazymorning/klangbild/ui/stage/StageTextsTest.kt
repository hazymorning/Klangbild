package io.github.hazymorning.klangbild.ui.stage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueuePlace
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import kotlin.test.Test

class StageTextsTest {
    private val de = TestTexts.german
    private val en = TestTexts.english()

    private fun lead(
        status: PlayerStatus = PlayerStatus.PLAYING,
        muted: Boolean = false,
        flash: Boolean = false,
        volume: String? = "35 %",
        group: String? = "Wohnzimmer",
        always: Boolean = false,
        t: Texts = de,
    ) = StageTexts.lead(status, muted, flash, volume, group, always, t)

    @Test
    fun `the place comes first, the state last`() {
        assertThat(lead(PlayerStatus.PAUSED)).isEqualTo("Wohnzimmer · Pausiert")
        assertThat(lead(muted = true)).isEqualTo("Wohnzimmer · Stumm")
        assertThat(lead(PlayerStatus.PAUSED, group = null)).isEqualTo("Pausiert")
        assertThat(lead(PlayerStatus.PAUSED, group = "Living room", t = en)).isEqualTo("Living room\u00A0· Paused")
        assertThat(lead(muted = true, group = "Kitchen", t = en)).isEqualTo("Kitchen\u00A0· Muted")
    }

    @Test
    fun `the track's place in its queue stands after the room, before the state`() {
        assertThat(StageTexts.lead(PlayerStatus.PLAYING, false, false, "35 %", "Wohnzimmer", false, de, place = "3 von 45")).isEqualTo("Wohnzimmer · 3 von 45")
        assertThat(StageTexts.lead(PlayerStatus.PAUSED, false, false, "35 %", "Wohnzimmer", false, de, place = "3 von 45")).isEqualTo("Wohnzimmer · 3 von 45 · Pausiert")
        assertThat(StageTexts.lead(PlayerStatus.PLAYING, true, false, "35 %", null, false, de, place = "3 von 45")).isEqualTo("3 von 45 · Stumm")
        assertThat(StageTexts.lead(PlayerStatus.PLAYING, false, false, "35 %", null, false, de, place = "3 von 45")).isEqualTo("3 von 45")
        // A volume change still shows alone for its moment.
        assertThat(StageTexts.lead(PlayerStatus.PLAYING, false, true, "35 %", "Wohnzimmer", false, de, place = "3 von 45")).isEqualTo("Lautstärke 35 %")
    }

    @Test
    fun `a passing volume wins, and stays out while muted`() {
        assertThat(lead(flash = true)).isEqualTo("Lautstärke 35 %")
        assertThat(lead(flash = true, muted = true)).isEqualTo("Wohnzimmer · Stumm")
    }

    @Test
    fun `playing alone says nothing, the volume only if always shown`() {
        assertThat(lead(group = null)).isNull()
        assertThat(lead()).isEqualTo("Wohnzimmer")
        assertThat(lead(always = true)).isEqualTo("Wohnzimmer · 35 %")
    }

    @Test
    fun `this TV's own speaker at full volume leaves its volume out, turned down it shows`() {
        assertThat(StageTexts.volumeAlways(always = true, own = true, level = 1f)).isFalse()
        assertThat(StageTexts.volumeAlways(always = true, own = true, level = 0.996f)).isFalse()
        assertThat(StageTexts.volumeAlways(always = true, own = true, level = 0.4f)).isTrue()
        assertThat(StageTexts.volumeAlways(always = true, own = true, level = null)).isTrue()
        assertThat(StageTexts.volumeAlways(always = true, own = false, level = 1f)).isTrue()
        assertThat(StageTexts.volumeAlways(always = false, own = false, level = 0.4f)).isFalse()
    }

    @Test
    fun `what comes next, and its title alone`() {
        val next = TrackInfo("Spiegel im Spiegel", listOf("Arvo Pärt"))
        assertThat(StageTexts.footer(next, de)).isEqualTo("Spiegel im Spiegel · Arvo Pärt")
        assertThat(StageTexts.footerTitle(next, de)).isEqualTo("Spiegel im Spiegel")
        assertThat(StageTexts.footer(null, de)).isNull()
    }

    @Test
    fun `a song's place in its queue, only for music in a queue of more than one`() {
        val song = TrackInfo("Spiegel im Spiegel", listOf("Arvo Pärt"))
        assertThat(StageTexts.place(QueuePlace(3, 45), song, en)).isEqualTo("3 of 45")
        assertThat(StageTexts.place(QueuePlace(3, 45), song, de)).isEqualTo("3 von 45")
        assertThat(StageTexts.place(QueuePlace(1234, 2000), song, en)).isEqualTo("1,234 of 2,000")
        assertThat(StageTexts.place(QueuePlace(1234, 2000), song, de)).isEqualTo("1.234 von 2.000")
        assertThat(StageTexts.place(QueuePlace(1, 1), song, en)).isNull()
        assertThat(StageTexts.place(null, song, en)).isNull()
        assertThat(StageTexts.place(QueuePlace(2, 9), song.copy(kind = MediaKind.RADIO), en)).isNull()
        assertThat(StageTexts.place(QueuePlace(2, 9), song.copy(kind = MediaKind.AUDIOBOOK), en)).isNull()
        assertThat(StageTexts.place(QueuePlace(2, 9), song.copy(kind = MediaKind.PODCAST), en)).isNull()
    }

    @Test
    fun `a radio shows its station, a lost link says so`() {
        val radio = TrackInfo("Morgenmusik", source = "Radio Morgenlicht")
        assertThat(StageTexts.slotText(false, radio, de)).isEqualTo("Radio Morgenlicht")
        assertThat(StageTexts.slotText(true, radio, de)).isEqualTo(de[Strings.stageLinkDown])
        assertThat(StageTexts.slotText(false, radio.copy(durationMs = 1000), de)).isNull()
    }
}
