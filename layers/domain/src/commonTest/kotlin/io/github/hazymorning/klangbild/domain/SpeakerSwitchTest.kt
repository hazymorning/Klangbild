package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/** When this TV's own speaker takes the screen, and when it gives it back. */
class SpeakerSwitchTest {
    private val song = TrackInfo(title = "Lied")

    private fun player(status: PlayerStatus, track: TrackInfo? = song) = PlayerSnapshot(status = status, track = track)

    @Test
    fun `music that starts here shows at once, also over a speaker that plays`() {
        val s = SpeakerSwitch(backMs = 4_000)
        assertThat(s.update(player(PlayerStatus.IDLE, null), player(PlayerStatus.PLAYING), 0)).isFalse()
        assertThat(s.update(player(PlayerStatus.BUFFERING), player(PlayerStatus.PLAYING), 100)).isTrue()
        assertThat(s.update(player(PlayerStatus.PLAYING), player(PlayerStatus.PLAYING), 200)).isTrue()
    }

    @Test
    fun `a paused song stays until the chosen speaker plays`() {
        val s = SpeakerSwitch(backMs = 4_000)
        s.update(player(PlayerStatus.PLAYING), player(PlayerStatus.IDLE), 0)
        assertThat(s.update(player(PlayerStatus.PAUSED), player(PlayerStatus.IDLE), 1_000)).isTrue()
        assertThat(s.update(player(PlayerStatus.PAUSED), player(PlayerStatus.IDLE), 600_000)).isTrue()
        assertThat(s.backAt).isNull()
        assertThat(s.update(player(PlayerStatus.PAUSED), player(PlayerStatus.PLAYING), 601_000)).isFalse()
        // Played on here, it comes back.
        assertThat(s.update(player(PlayerStatus.PLAYING), player(PlayerStatus.PLAYING), 602_000)).isTrue()
    }

    @Test
    fun `a paused song gives way to another room's music once a paused stage would`() {
        val s = SpeakerSwitch(backMs = 4_000, pauseMs = 180_000)
        s.update(player(PlayerStatus.PLAYING), player(PlayerStatus.IDLE), 0)
        assertThat(s.update(player(PlayerStatus.PAUSED), player(PlayerStatus.IDLE), 1_000, elsewhere = player(PlayerStatus.PLAYING))).isTrue()
        assertThat(s.backAt).isEqualTo(181_000)
        assertThat(s.update(player(PlayerStatus.PAUSED), player(PlayerStatus.IDLE), 180_999, elsewhere = player(PlayerStatus.PLAYING))).isTrue()
        assertThat(s.update(player(PlayerStatus.PAUSED), player(PlayerStatus.IDLE), 181_000, elsewhere = player(PlayerStatus.PLAYING))).isFalse()
    }

    @Test
    fun `stopped music gives the screen back after a moment, and music in between keeps it`() {
        val s = SpeakerSwitch(backMs = 4_000)
        s.update(player(PlayerStatus.PLAYING), null, 0)
        assertThat(s.update(player(PlayerStatus.IDLE, null), null, 10_000)).isTrue()
        assertThat(s.backAt).isEqualTo(14_000)
        assertThat(s.update(player(PlayerStatus.IDLE, null), null, 13_999)).isTrue()
        // The next song starts in between: the stop is forgotten.
        assertThat(s.update(player(PlayerStatus.PLAYING), null, 13_999)).isTrue()
        assertThat(s.backAt).isNull()
        assertThat(s.update(player(PlayerStatus.OFF, null), null, 20_000)).isTrue()
        assertThat(s.update(null, null, 24_000)).isFalse()
        assertThat(s.backAt).isNull()
    }

    @Test
    fun `nothing here never takes the screen, and a reset gives it back at once`() {
        val s = SpeakerSwitch(backMs = 4_000)
        assertThat(s.update(player(PlayerStatus.PAUSED), null, 0)).isFalse()
        assertThat(s.update(player(PlayerStatus.PLAYING, null), null, 0)).isFalse()
        assertThat(s.backAt).isNull()
        s.update(player(PlayerStatus.PLAYING), null, 0)
        s.reset()
        assertThat(s.own).isFalse()
    }
}
