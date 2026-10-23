package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.text.TestTexts
import kotlin.test.Test

class SpeakerLabelTest {
    private val de = TestTexts.german
    private val en = TestTexts.english()

    @Test
    fun `a group is this speaker plus the others`() {
        val all = SpeakerGroup("Wohnzimmer", listOf("Küche", "Bad", "Büro", "Flur", "Terrasse"))
        assertThat(SpeakerLabel.group(all, de)).isEqualTo("Wohnzimmer + 5")
        // Just one more: both names.
        assertThat(SpeakerLabel.group(SpeakerGroup("Wohnzimmer", listOf("Küche")), de)).isEqualTo("Wohnzimmer + Küche")
        // Its name not known yet: counted.
        assertThat(SpeakerLabel.group(SpeakerGroup("Wohnzimmer", listOf(null)), de)).isEqualTo("Wohnzimmer + 1")
        assertThat(SpeakerLabel.group(SpeakerGroup(null, listOf("Küche", "Bad")), de)).isEqualTo("Drei Lautsprecher")
        // Alone: nothing to say, since the screen hangs in that room.
        assertThat(SpeakerLabel.group(SpeakerGroup("Wohnzimmer", emptyList()), de)).isNull()
        assertThat(SpeakerLabel.group(null, de)).isNull()
        // Every speaker of the house.
        assertThat(SpeakerLabel.group(SpeakerGroup("Wohnzimmer", listOf("Küche", "Bad"), everywhere = true), de)).isEqualTo("Überall")
    }

    @Test
    fun `a group with a name of its own is called by it`() {
        assertThat(SpeakerLabel.group(SpeakerGroup("Wohnzimmer", emptyList(), name = "Alle Lautsprecher"), de)).isEqualTo("Alle Lautsprecher")
        assertThat(SpeakerLabel.group(SpeakerGroup(null, listOf("Küche", "Bad"), name = "Erdgeschoss"), de)).isEqualTo("Erdgeschoss")
        // The queue of a speaker playing alone carries its own name, in whatever spelling.
        assertThat(SpeakerLabel.isOtherName("Wohnzimmer", "Wohnzimmer Lautsprecher")).isFalse()
        assertThat(SpeakerLabel.isOtherName("Sonos Wohnzimmer", "Wohnzimmer")).isFalse()
        assertThat(SpeakerLabel.isOtherName("wohnzimmer", "Wohnzimmer (MA)")).isFalse()
        assertThat(SpeakerLabel.isOtherName("Erdgeschoss", "Wohnzimmer")).isTrue()
        assertThat(SpeakerLabel.isOtherName("Alle", null)).isTrue()
        assertThat(SpeakerLabel.isOtherName(" ", "Wohnzimmer")).isFalse()
    }

    @Test
    fun `room names lose the device words`() {
        assertThat(SpeakerLabel.roomName("Wohnzimmer Lautsprecher")).isEqualTo("Wohnzimmer")
        assertThat(SpeakerLabel.roomName("Sonos One Küche")).isEqualTo("Küche")
        assertThat(SpeakerLabel.roomName("Wohnzimmer (MA)")).isEqualTo("Wohnzimmer")
        assertThat(SpeakerLabel.roomName("Sonos One (Bad)")).isEqualTo("Bad")
        assertThat(SpeakerLabel.roomName("Wohnzimmerlautsprecher")).isEqualTo("Wohnzimmer")
        assertThat(SpeakerLabel.roomName("Bad-Speaker")).isEqualTo("Bad")
        assertThat(SpeakerLabel.roomName("Home Office")).isEqualTo("Home Office")
        assertThat(SpeakerLabel.roomName("Musikzimmer")).isEqualTo("Musikzimmer")
        // Nothing but device words: the full name stays.
        assertThat(SpeakerLabel.roomName("Sonos One")).isEqualTo("Sonos One")
        assertThat(SpeakerLabel.roomName("  ")).isNull()
    }

    @Test
    fun `in English`() {
        assertThat(SpeakerLabel.group(SpeakerGroup("Living room", listOf("Kitchen", "Bath")), en)).isEqualTo("Living room\u00A0+\u00A02")
        assertThat(SpeakerLabel.group(SpeakerGroup("Living room", listOf("Kitchen")), en)).isEqualTo("Living room\u00A0+ Kitchen")
        assertThat(SpeakerLabel.group(SpeakerGroup(null, listOf("Kitchen", "Bath")), en)).isEqualTo("Three speakers")
        assertThat(SpeakerLabel.group(SpeakerGroup(null, (1..12).map { "Room $it" }), en)).isEqualTo("13 speakers")
        assertThat(SpeakerLabel.group(SpeakerGroup("Living room", listOf("Kitchen"), everywhere = true), en)).isEqualTo("Everywhere")
        assertThat(SpeakerLabel.volume(0.35f, muted = false, en)).isEqualTo("35%")
        assertThat(SpeakerLabel.volume(0.35f, muted = true, en)).isEqualTo("muted")
    }

    @Test
    fun `volume in figures with a no-break space`() {
        assertThat(SpeakerLabel.volume(0.35f, muted = false, de)).isEqualTo("35 %")
        assertThat(SpeakerLabel.volume(1.2f, muted = false, de)).isEqualTo("100 %")
        assertThat(SpeakerLabel.volume(0.35f, muted = true, de)).isEqualTo("stumm")
        assertThat(SpeakerLabel.volume(null, muted = false, de)).isNull()
    }
}
