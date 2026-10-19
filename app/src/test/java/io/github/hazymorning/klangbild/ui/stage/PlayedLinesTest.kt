package io.github.hazymorning.klangbild.ui.stage

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.TrackInfo
import org.junit.Test

/** A song that no longer plays keeps what its lines showed while it played. */
class PlayedLinesTest {
    private val b = TrackInfo("Who Has Seen the Wind", listOf("Willow Static"))
    private val c = TrackInfo("Cold Light", listOf("Willow Static"))

    @Test
    fun aSongThatNoLongerPlaysKeepsItsOwnNext() {
        val lines = PlayedLines()
        assertThat(lines.next("a", plays = true, now = b)).isEqualTo(b)
        // B plays now, and C comes after it: A, still on screen, keeps its own next.
        assertThat(lines.next("a", plays = false, now = c)).isEqualTo(b)
        assertThat(lines.next("b", plays = true, now = c)).isEqualTo(c)
    }

    @Test
    fun aSongThatNoLongerPlaysKeepsWhereItStood() {
        val lines = PlayedLines()
        var now = 65_000L
        assertThat(lines.position("a", plays = true) { now }).isEqualTo(65_000L)
        now = 2_000L
        assertThat(lines.position("a", plays = false) { now }).isEqualTo(65_000L)
        assertThat(lines.position("b", plays = true) { now }).isEqualTo(2_000L)
    }

    @Test
    fun aReadThatKnowsNothingKeepsTheLastPlace() {
        val lines = PlayedLines()
        assertThat(lines.position("a", plays = true) { 65_000L }).isEqualTo(65_000L)
        // The player reports the next song already, or nothing between two songs.
        assertThat(lines.position("a", plays = true) { null }).isEqualTo(65_000L)
    }

    @Test
    fun aSongThatNoLongerPlaysKeepsItsChapter() {
        val lines = PlayedLines()
        val seven = ChapterPlace(6, 12, "Chapter 7", 600_000L, 900_000L, "Chapter 8")
        assertThat(lines.chapter("book", plays = true, now = seven)).isEqualTo(seven)
        assertThat(lines.chapter("book", plays = false, now = null)).isEqualTo(seven)
    }

    @Test
    fun aSongNeverSeenPlayingHasNothingOfItsOwn() {
        val lines = PlayedLines()
        assertThat(lines.next("x", plays = false, now = c)).isNull()
        assertThat(lines.position("x", plays = false) { 1_000L }).isNull()
    }

    @Test
    fun onlyTheLastFewSongsAreKept() {
        val lines = PlayedLines()
        for (k in 0 until 10) lines.next("s$k", plays = true, now = b)
        assertThat(lines.next("s0", plays = false, now = c)).isNull()
        assertThat(lines.next("s9", plays = false, now = c)).isEqualTo(b)
    }
}
