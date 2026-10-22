package io.github.hazymorning.klangbild.ui.stage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
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
        assertThat(StageTexts.next(next, de)).isEqualTo("Spiegel im Spiegel · Arvo Pärt")
        assertThat(StageTexts.nextTitle(next, de)).isEqualTo("Spiegel im Spiegel")
        assertThat(StageTexts.next(null, de)).isNull()
    }

    @Test
    fun `a radio shows its station, a lost link says so`() {
        val radio = TrackInfo("Morgenmusik", source = "Radio Morgenlicht")
        assertThat(StageTexts.line(false, radio, de)).isEqualTo("Radio Morgenlicht")
        assertThat(StageTexts.line(true, radio, de)).isEqualTo(de[R.string.stage_link_down])
        assertThat(StageTexts.line(true, radio, de)).isEqualTo("Verbindung wird wiederhergestellt\u00A0…")
        assertThat(StageTexts.line(false, radio.copy(durationMs = 1000), de)).isNull()
    }
}
