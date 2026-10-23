package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/** Sleep timer: the music pauses once, after some minutes or when playing reaches an end. */
class SleepTimerTest {
    private val book = TrackInfo(
        title = "Der Process",
        durationMs = 3_000_000L,
        kind = MediaKind.AUDIOBOOK,
        chapters = listOf(Chapter("A", 0, 1_000_000), Chapter("B", 1_000_000, 2_000_000), Chapter("C", 2_000_000, 3_000_000)),
    )
    private val song = TrackInfo(title = "Lied", durationMs = 200_000L)
    private val nextSong = TrackInfo(title = "Nächstes Lied", durationMs = 180_000L)

    @Test
    fun `minutes pause the music once, and only if it plays`() {
        val timer = SleepTimer()
        timer.set(SleepChoice.MIN_15, nowMs = 0)
        assertThat(timer.pausesAt).isEqualTo(15 * 60_000L)
        assertThat(timer.evaluate(14 * 60_000L, song, 1_000, 1f, playing = true)).isFalse()
        assertThat(timer.evaluate(15 * 60_000L, song, 1_000, 1f, playing = true)).isTrue()
        assertThat(timer.choice).isEqualTo(SleepChoice.OFF)
        assertThat(timer.evaluate(16 * 60_000L, song, 1_000, 1f, playing = true)).isFalse()

        // Already paused when it runs out: nothing to do, and it is off all the same.
        timer.set(SleepChoice.MIN_30, nowMs = 0)
        assertThat(timer.evaluate(31 * 60_000L, song, 1_000, 1f, playing = false)).isFalse()
        assertThat(timer.choice).isEqualTo(SleepChoice.OFF)
    }

    @Test
    fun `the end of the chapter, reached by playing`() {
        val timer = SleepTimer()
        timer.set(SleepChoice.CHAPTER_END, nowMs = 0)
        assertThat(timer.evaluate(0, book, 1_990_000, 1f, playing = true)).isFalse()
        // It expects the end in ten seconds, or in five at 2×.
        assertThat(timer.wakeAt).isEqualTo(10_000L)
        assertThat(timer.evaluate(1_000, book, 1_992_000, 2f, playing = true)).isFalse()
        assertThat(timer.wakeAt).isEqualTo(1_000L + 4_000L)
        assertThat(timer.evaluate(5_000, book, 2_000_010, 2f, playing = true)).isTrue()
        assertThat(timer.choice).isEqualTo(SleepChoice.OFF)
    }

    @Test
    fun `a jump across the end moves the timer on to the next end`() {
        val timer = SleepTimer()
        timer.set(SleepChoice.CHAPTER_END, nowMs = 0)
        timer.evaluate(0, book, 1_990_000, 1f, playing = true)
        // Thirty seconds ahead: past the end of B and into C. No pause; it now waits for C's end.
        assertThat(timer.evaluate(500, book, 2_020_500, 1f, playing = true)).isFalse()
        assertThat(timer.choice).isEqualTo(SleepChoice.CHAPTER_END)
        // C is the last chapter: it ends with the book, a moment before the very end.
        assertThat(timer.wakeAt).isEqualTo(500L + (3_000_000L - SleepTimer.ITEM_LEAD_MS - 2_020_500L))
        assertThat(timer.evaluate(978_000, book, 2_998_100, 1f, playing = true)).isFalse()
        assertThat(timer.evaluate(978_600, book, 2_998_600, 1f, playing = true)).isTrue()
    }

    @Test
    fun `paused, an end waits`() {
        val timer = SleepTimer()
        timer.set(SleepChoice.CHAPTER_END, nowMs = 0)
        timer.evaluate(0, book, 1_999_000, 1f, playing = false)
        assertThat(timer.wakeAt).isNull()
        assertThat(timer.evaluate(60_000, book, 1_999_000, 1f, playing = false)).isFalse()
        // Playing again, it goes on waiting for the same end.
        assertThat(timer.evaluate(61_000, book, 1_999_500, 1f, playing = true)).isFalse()
        assertThat(timer.evaluate(62_000, book, 2_000_400, 1f, playing = true)).isTrue()
    }

    @Test
    fun `the end of a song, before the queue moves on, or right after if it was missed`() {
        val timer = SleepTimer()
        timer.set(SleepChoice.ITEM_END, nowMs = 0)
        timer.evaluate(0, song, 190_000, 1f, playing = true)
        assertThat(timer.wakeAt).isEqualTo(10_000L - SleepTimer.ITEM_LEAD_MS)
        assertThat(timer.evaluate(8_600, song, 198_600, 1f, playing = true)).isTrue()

        // Missed: the next song has begun right after the end of the last one.
        timer.set(SleepChoice.ITEM_END, nowMs = 0)
        timer.evaluate(0, song, 197_000, 1f, playing = true)
        assertThat(timer.evaluate(4_000, nextSong, 1_000, 1f, playing = true)).isTrue()

        // Skipped to the next song long before the end: it waits for that one's end.
        timer.set(SleepChoice.ITEM_END, nowMs = 0)
        timer.evaluate(0, song, 50_000, 1f, playing = true)
        assertThat(timer.evaluate(1_000, nextSong, 500, 1f, playing = true)).isFalse()
        assertThat(timer.choice).isEqualTo(SleepChoice.ITEM_END)
    }

    @Test
    fun `the choices fit what plays`() {
        val timer = SleepTimer()
        assertThat(timer.choices(book)).containsAtLeast(SleepChoice.CHAPTER_END, SleepChoice.ITEM_END)
        assertThat(timer.choices(song)).doesNotContain(SleepChoice.CHAPTER_END)
        assertThat(timer.choices(song)).contains(SleepChoice.ITEM_END)
        val station = TrackInfo(title = "SWR2", kind = MediaKind.RADIO)
        assertThat(timer.choices(station)).containsExactlyInAnyOrder(
            SleepChoice.OFF, SleepChoice.MIN_15, SleepChoice.MIN_30, SleepChoice.MIN_45, SleepChoice.MIN_60,
        )
        // A choice that no longer fits stays until it is changed.
        timer.set(SleepChoice.CHAPTER_END, 0)
        assertThat(timer.choices(song)).contains(SleepChoice.CHAPTER_END)
    }
}
