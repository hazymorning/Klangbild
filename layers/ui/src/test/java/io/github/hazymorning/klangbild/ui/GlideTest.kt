package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A list's glide ([animateGlide]) frame by frame: a press glides one line as it always did; a
 * held key runs the list on evenly instead of starting over from a standstill at every repeat
 * (which read as lag), and once let go the list comes to rest on its line without passing it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlideTest {
    @get:Rule
    val compose = composeRule()

    /** Down held: a press, then the remote's repeats every 50 ms from 400 ms, a line (40 dp) each. */
    private val held = listOf(0L to LINE) + (0 until 14).map { i -> 400L + 50L * i to LINE * (i + 2) }

    /** Where the glide stands frame by frame (ms, dp) while the targets of [steps] come at their times (ms). */
    private fun frames(steps: List<Pair<Long, Float>>, untilMs: Long): List<Pair<Long, Float>> {
        var target by mutableFloatStateOf(0f)
        lateinit var glide: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent { glide = animateGlide(target, GLIDE_MS) }
        val out = ArrayList<Pair<Long, Float>>()
        var t = 0L
        var next = 0
        while (t <= untilMs) {
            while (next < steps.size && steps[next].first <= t) target = steps[next++].second
            // A state written outside a frame reaches the composition once it is announced.
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(FRAME_MS)
            t += FRAME_MS
            out += t to glide.value
        }
        return out
    }

    private fun targetAt(t: Long) = held.last { it.first < t }.second

    @Test
    fun `a press glides one line in its own time and stops there`() {
        val f = frames(listOf(0L to LINE), untilMs = 600)
        // Eased in: the first frame barely moves; then it arrives and stays.
        assertThat(f.first().second).isLessThan(2f)
        assertThat(f.first { it.second == LINE }.first).isIn(Range.closed(GLIDE_MS.toLong(), GLIDE_MS + 4 * FRAME_MS))
        assertThat(f.maxOf { it.second }).isAtMost(LINE)
    }

    @Test
    fun `a held key runs the list on evenly, a few lines behind`() {
        val release = held.last().first
        // Under way from the third repeat: every frame moves on, none much less than the others.
        val run = frames(held, untilMs = release).filter { it.first in 550L..release }
        val steps = run.zipWithNext { a, b -> b.second - a.second }
        assertThat(steps.min()).isGreaterThan(0.5f * steps.average().toFloat())
        for ((t, v) in run) assertThat(targetAt(t) - v).isLessThan(3 * LINE)
    }

    @Test
    fun `let go, the list comes to rest on its line without passing it`() {
        val release = held.last().first
        val end = held.last().second
        val after = frames(held, untilMs = release + 1_000).filter { it.first > release }
        assertThat(after.maxOf { it.second }).isAtMost(end + 0.5f)
        assertThat(after.first { end - it.second < 0.5f }.first - release).isAtMost(600L)
    }

    private companion object {
        const val LINE = 40f
        const val GLIDE_MS = 340
        const val FRAME_MS = 16L
    }
}
