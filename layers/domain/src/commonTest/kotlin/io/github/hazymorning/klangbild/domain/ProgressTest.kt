package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class ProgressTest {
    private val track = TrackInfo("Song", durationMs = 200_000)

    private fun snap(status: PlayerStatus, pos: Long?, at: Long?) =
        PlayerSnapshot(status = status, track = track, positionMs = pos, positionAtEpochMs = at)

    @Test
    fun `extrapolates while playing`() {
        val s = snap(PlayerStatus.PLAYING, 60_000, at = 1_000_000)
        assertThat(Progress.positionAt(s, nowMs = 1_010_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(70_000)
    }

    @Test
    fun `removes clock skew`() {
        // The TV clock runs 3 s ahead of Home Assistant.
        val s = snap(PlayerStatus.PLAYING, 60_000, at = 1_000_000)
        assertThat(Progress.positionAt(s, nowMs = 1_013_000, skewMs = 3_000, receivedAtMs = 0)).isEqualTo(70_000)
    }

    @Test
    fun `holds when paused and clamps to the duration`() {
        val paused = snap(PlayerStatus.PAUSED, 60_000, at = 1_000_000)
        assertThat(Progress.positionAt(paused, nowMs = 9_000_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(60_000)
        val late = snap(PlayerStatus.PLAYING, 199_000, at = 1_000_000)
        assertThat(Progress.positionAt(late, nowMs = 1_500_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(200_000)
    }

    @Test
    fun `falls back to receive time without a server timestamp`() {
        val s = snap(PlayerStatus.PLAYING, 10_000, at = null)
        assertThat(Progress.positionAt(s, nowMs = 5_000, skewMs = 999, receivedAtMs = 3_000)).isEqualTo(12_000)
        assertThat(Progress.positionAt(snap(PlayerStatus.PLAYING, null, null), 0, 0, 0)).isNull()
    }

    @Test
    fun `the position runs at the playback speed`() {
        val s = snap(PlayerStatus.PLAYING, 10_000, at = 1_000_000).copy(speed = 1.5f)
        assertThat(Progress.positionAt(s, nowMs = 1_004_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(16_000)
        assertThat(Progress.positionAt(s.copy(status = PlayerStatus.PAUSED), nowMs = 1_004_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(10_000)
    }

    @Test
    fun `small corrections glide, seeks jump`() {
        val smooth = SmoothedPosition(glideLimitMs = 2_500, tauMs = 700.0)
        smooth.onReport("a", rawNowMs = 10_000, nowMs = 0)
        assertThat(smooth.display("a", 10_000, 0)).isEqualTo(10_000)
        // Server says we are 1 s behind what is shown: glide, do not jump.
        smooth.onReport("a", rawNowMs = 9_000, nowMs = 0)
        assertThat(smooth.display("a", 9_000, 0)).isEqualTo(10_000)
        assertThat(smooth.display("a", 9_700, 700)).isNotNull().isBetween(9_700L, 10_100L)
        assertThat(smooth.display("a", 13_000, 4_000)).isNotNull().isBetween(13_000L, 13_010L)
        // A seek of 60 s is shown immediately.
        smooth.onReport("a", rawNowMs = 73_000, nowMs = 4_000)
        assertThat(smooth.display("a", 73_000, 4_000)).isEqualTo(73_000)
        // A new track never glides.
        smooth.onReport("b", rawNowMs = 0, nowMs = 5_000)
        assertThat(smooth.display("b", 0, 5_000)).isEqualTo(0)
    }

    @Test
    fun `a new song never glides from the time of the song before`() {
        val smooth = SmoothedPosition(glideLimitMs = 2_500, tauMs = 700.0)
        smooth.onReport("a", rawNowMs = 2_600, nowMs = 0)
        assertThat(smooth.display("a", 2_600, 0)).isEqualTo(2_600)
        // B is reported twice before anything shows its time: the second report has nothing of
        // its own to glide from, and never takes A's 0:02.6 for it (B's time would run backwards).
        smooth.onReport("b", rawNowMs = 0, nowMs = 100)
        smooth.onReport("b", rawNowMs = 400, nowMs = 500)
        assertThat(smooth.display("b", 400, 500)).isEqualTo(400)
        // A read for A meanwhile (its stage still on screen) leaves B's smoothing alone.
        assertThat(smooth.display("a", 2_700, 500)).isEqualTo(2_700)
        smooth.onReport("b", rawNowMs = 400, nowMs = 500)
        assertThat(smooth.display("b", 400, 500)).isEqualTo(400)
    }
}
