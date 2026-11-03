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
        val smooth = SmoothedPosition()
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
        val smooth = SmoothedPosition()
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

    // In the reports below, the song stands at (server time − 990 s): 10 s at 1_000_000. It
    // starts, as far as the reports go, at local time 0 and settles for ten seconds.
    private fun playing(pos: Long, at: Long?, song: TrackInfo = track) =
        PlayerSnapshot(status = PlayerStatus.PLAYING, track = song, positionMs = pos, positionAtEpochMs = at)

    private fun PositionReports.started(): PositionReports = apply { best(playing(10_000, at = 1_000_000), nowMs = 0, skewMs = 0, receivedAtMs = 0) }

    @Test
    fun `of reports a little late, the one that runs furthest ahead counts`() {
        val reports = PositionReports().started()
        // Whole seconds and the way to here: 0.7 s, then 0.2 s, then 0.9 s late.
        val first = playing(20_000, at = 1_010_700)
        assertThat(reports.best(first, nowMs = 10_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(first)
        val closer = playing(25_000, at = 1_015_200)
        assertThat(reports.best(closer, nowMs = 15_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(closer)
        assertThat(reports.best(playing(30_000, at = 1_020_900), nowMs = 20_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(closer)
        // The same report again changes nothing.
        assertThat(reports.best(playing(30_000, at = 1_020_900), nowMs = 21_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(closer)
    }

    @Test
    fun `while the song settles the latest report counts, and an early one is left out after`() {
        val reports = PositionReports()
        // Right after a start, the player counts from when it was asked to play: half a second early.
        val early = playing(10_500, at = 1_000_000)
        assertThat(reports.best(early, nowMs = 0, skewMs = 0, receivedAtMs = 0)).isEqualTo(early)
        val late = playing(14_200, at = 1_005_000)
        assertThat(reports.best(late, nowMs = 5_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(late)
        val settled = playing(20_000, at = 1_010_300)
        assertThat(reports.best(settled, nowMs = 10_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(settled)
    }

    @Test
    fun `a jump either way counts at once, and the song settles anew`() {
        val reports = PositionReports().started()
        val onTime = playing(20_000, at = 1_010_000)
        reports.best(onTime, nowMs = 10_000, skewMs = 0, receivedAtMs = 0)
        // A second behind is late.
        assertThat(reports.best(playing(24_000, at = 1_015_000), nowMs = 15_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(onTime)
        // Ten seconds behind is a jump back; settling again, the latest report counts.
        val back = playing(15_000, at = 1_016_000)
        assertThat(reports.best(back, nowMs = 16_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(back)
        val behindIt = playing(19_500, at = 1_021_000)
        assertThat(reports.best(behindIt, nowMs = 21_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(behindIt)
        val ahead = playing(60_000, at = 1_022_000)
        assertThat(reports.best(ahead, nowMs = 22_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(ahead)
    }

    @Test
    fun `a pause, another track or another speed starts afresh`() {
        val reports = PositionReports().started()
        reports.best(playing(20_000, at = 1_010_000), nowMs = 10_000, skewMs = 0, receivedAtMs = 0)
        val paused = playing(23_000, at = 1_013_000).copy(status = PlayerStatus.PAUSED)
        assertThat(reports.best(paused, nowMs = 13_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(paused)
        // Without the pause, and then without the other track, these would count as a second late.
        val again = playing(23_000, at = 1_014_000)
        assertThat(reports.best(again, nowMs = 14_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(again)
        val other = playing(23_000, at = 1_015_000, song = TrackInfo("Other song", durationMs = 200_000))
        assertThat(reports.best(other, nowMs = 15_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(other)
        val book = other.copy(speed = 1.5f)
        assertThat(reports.best(book, nowMs = 16_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(book)
        // At 1.5×, 15 s on after 10 s is on time, and 22 s on after 15 s half a second late.
        val onTime = book.copy(positionMs = 38_000, positionAtEpochMs = 1_025_000)
        assertThat(reports.best(onTime, nowMs = 26_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(onTime)
        assertThat(reports.best(book.copy(positionMs = 45_000, positionAtEpochMs = 1_030_000), nowMs = 31_000, skewMs = 0, receivedAtMs = 0))
            .isEqualTo(onTime)
    }

    @Test
    fun `reports older than half a minute no longer count`() {
        val reports = PositionReports().started()
        val onTime = playing(20_000, at = 1_010_000)
        reports.best(onTime, nowMs = 10_000, skewMs = 0, receivedAtMs = 0)
        val late = playing(34_500, at = 1_025_000)
        assertThat(reports.best(late, nowMs = 25_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(onTime)
        assertThat(reports.best(late, nowMs = 41_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(late)
    }

    @Test
    fun `a report without a time of its own counts from when it arrived`() {
        // This clock runs 300 ms ahead of the server's.
        val reports = PositionReports()
        reports.best(playing(5_000, at = 995_000), nowMs = 995_300, skewMs = 300, receivedAtMs = 0)
        // Arrived at 1_005_300 on this clock, 1_005_000 on the server's.
        val untimed = playing(15_000, at = null)
        assertThat(reports.best(untimed, nowMs = 1_005_300, skewMs = 300, receivedAtMs = 1_005_300)).isEqualTo(untimed)
        val best = reports.best(playing(19_500, at = 1_010_000), nowMs = 1_010_300, skewMs = 300, receivedAtMs = 1_005_300)
        assertThat(best.positionMs).isEqualTo(15_000L)
        assertThat(best.positionAtEpochMs).isEqualTo(1_005_000L)
    }
}
