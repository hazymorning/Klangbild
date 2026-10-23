package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.MonotonicFrameClock
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.ui.stage.TextLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test

/** One change at a time, in virtual time: how a change hurries, and how its text holds, hands over and rises. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangesTest {
    /** A frame every 16 ms of the test's virtual time. */
    private class Frames(private val now: () -> Long) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            return onFrame(now() * 1_000_000L)
        }
    }

    private fun frames(block: suspend TestScope.() -> Unit) = runTest {
        val scope = this
        withContext(Frames { scope.currentTime }) { scope.block() }
    }

    private val rise = TextRise(500, 1_500)

    /** A change of 1.5 s whose new text rises as [rise]; another song comes after [skipAfterMs]. */
    private suspend fun skippedChange(t: Animatable<Float, AnimationVector1D>, text: RisingText, skipAfterMs: Long) {
        t.snapTo(0f)
        text.begin(rise)
        playChange(t, 1_500, newer = { delay(skipAfterMs) }) { text.hold(it) }
        text.landed()
    }

    @Test
    fun aChangeRunsItsWholeLengthWhenNothingNewerComes() = frames {
        val t = Animatable(0f)
        val start = currentTime
        playChange(t, 1_000, newer = { awaitCancellation() }) { error("nothing came") }
        assertThat(t.value).isEqualTo(1f)
        assertThat(currentTime - start).isGreaterThanOrEqualTo(1_000L)
    }

    @Test
    fun anotherSongHurriesTheRestButTheChangeStillLands() = frames {
        val t = Animatable(0f)
        var hurriedAt: Float? = null
        val start = currentTime
        playChange(t, 1_500, newer = { delay(300) }) { hurriedAt = it }
        assertThat(hurriedAt!!).isCloseTo(0.2f, 0.03f)
        assertThat(t.value).isEqualTo(1f)
        assertThat(currentTime - start).isLessThanOrEqualTo(300L + Motion.HURRY_MS + 50)
    }

    @Test
    fun theLinesOfASongSkippedPastStopWhereTheyStood() = frames {
        val t = Animatable(1f)
        val text = RisingText(t)
        skippedChange(t, text, 600)
        val title = text.level(TextLine.TITLE)
        assertThat(title).isGreaterThan(0.1f)
        assertThat(title).isLessThan(0.95f)
        assertThat(text.level(TextLine.NEXT)).isEqualTo(0f)
        assertThat(text.rising).isTrue()
        // A change that brings no new text (cards beside the middle) leaves them where they are.
        t.snapTo(0f)
        playChange(t, 900, newer = { awaitCancellation() }) { text.hold(it) }
        text.landed()
        assertThat(text.level(TextLine.TITLE)).isEqualTo(title)
    }

    @Test
    fun theNextSongsTextTakesOverFromWhereTheHeldLinesStood() = frames {
        val t = Animatable(1f)
        val text = RisingText(t)
        skippedChange(t, text, 600)
        val title = text.level(TextLine.TITLE)
        t.snapTo(0f)
        text.begin(rise)
        assertThat(text.leaving(TextLine.TITLE)).isEqualTo(title)
        assertThat(text.leaving(TextLine.NEXT)).isEqualTo(0f)
        assertThat(text.level(TextLine.TITLE)).isEqualTo(0f)
    }

    @Test
    fun heldLinesRiseTheRestOfTheWayIfTheSkipCameToNothing() = frames {
        val t = Animatable(1f)
        val text = RisingText(t)
        skippedChange(t, text, 600)
        val start = currentTime
        text.release(newer = { awaitCancellation() })
        assertThat(text.rising).isFalse()
        TextLine.entries.forEach { assertThat(text.level(it)).isEqualTo(1f) }
        // At the pace of the change: the rest of it.
        assertThat(currentTime - start).isGreaterThanOrEqualTo(800L)
    }

    @Test
    fun linesHeldBeforeTheySetOffRiseAtOnceWhenReleased() = frames {
        // Skipped 150 ms in, long before the first line (500 ms): released, the lines set off
        // right away instead of waiting out the rest of a change that has already landed.
        val t = Animatable(1f)
        val text = RisingText(t)
        skippedChange(t, text, 150)
        val start = currentTime
        text.release(newer = { awaitCancellation() })
        assertThat(text.level(TextLine.NEXT)).isEqualTo(1f)
        assertThat(currentTime - start).isLessThanOrEqualTo(1_000L + 50)
    }

    @Test
    fun anotherSongStopsTheReleaseWhereItIs() = frames {
        val t = Animatable(1f)
        val text = RisingText(t)
        skippedChange(t, text, 600)
        assertThat(text.level(TextLine.NEXT)).isEqualTo(0f)
        text.release(newer = { delay(300) })
        assertThat(text.rising).isTrue()
        assertThat(text.level(TextLine.NEXT)).isGreaterThan(0.2f)
        assertThat(text.level(TextLine.NEXT)).isLessThan(0.95f)
    }

    @Test
    fun everyLineIsInPlaceWhenItsChangeEnds() {
        // A change that is not hurried hands over to text standing still: no line may still be on its way.
        val rises = listOf(TextRise(Motion.TEXT_IN_MS, Motion.TRACK_CHANGE_MS), TextRise(Motion.TEXT_IN_SAME_PICTURE_MS, Motion.TEXT_CHANGE_MS))
        for (r in rises) TextLine.entries.forEach { assertThat(r.level(1f, it)).isEqualTo(1f) }
    }
}
