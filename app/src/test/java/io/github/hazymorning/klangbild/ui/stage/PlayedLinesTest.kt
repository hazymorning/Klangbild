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
        assertThat(lines.next("a", playing = true, live = b)).isEqualTo(b)
        // B plays now, and C comes after it: A, still on screen, keeps its own next.
        assertThat(lines.next("a", playing = false, live = c)).isEqualTo(b)
        assertThat(lines.next("b", playing = true, live = c)).isEqualTo(c)
    }

    @Test
    fun aSongThatNoLongerPlaysKeepsWhereItStood() {
        val lines = PlayedLines()
        var now = 65_000L
        assertThat(lines.position("a", playing = true) { now }).isEqualTo(65_000L)
        now = 2_000L
        assertThat(lines.position("a", playing = false) { now }).isEqualTo(65_000L)
        assertThat(lines.position("b", playing = true) { now }).isEqualTo(2_000L)
    }

    @Test
    fun aSongThatNoLongerPlaysKeepsItsChapter() {
        val lines = PlayedLines()
        val seven = ChapterPlace(6, 12, "Chapter 7", 600_000L, 900_000L, "Chapter 8")
        assertThat(lines.chapter("book", playing = true, live = seven)).isEqualTo(seven)
        assertThat(lines.chapter("book", playing = false, live = null)).isEqualTo(seven)
    }

    @Test
    fun aSongNeverSeenPlayingHasNothingOfItsOwn() {
        val lines = PlayedLines()
        assertThat(lines.next("x", playing = false, live = c)).isNull()
        assertThat(lines.position("x", playing = false) { 1_000L }).isNull()
    }

    @Test
    fun onlyTheLastFewSongsAreKept() {
        val lines = PlayedLines()
        for (k in 0 until 10) lines.next("s$k", playing = true, live = b)
        assertThat(lines.next("s0", playing = false, live = c)).isNull()
        assertThat(lines.next("s9", playing = false, live = c)).isEqualTo(b)
    }
}
