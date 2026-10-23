package io.github.hazymorning.klangbild.ui.landscape

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import io.github.hazymorning.klangbild.landscape.BakeProgress
import kotlin.test.Test

class WaitingPaceTest {
    private val pace = WaitingPace()
    private var now = 0.0

    /** Frames at 60 a second until [until] (s), the bake at [baked]. */
    private fun run(until: Double, baked: BakeProgress, ready: Boolean = false): Float {
        var f = pace.filled
        while (now < until - 1e-9) {
            now += 1.0 / 60
            f = pace.at(now, baked, ready)
        }
        return f
    }

    @Test
    fun `between two reports the bar runs on at the pace so far, up to the end of the part under way`() {
        run(1.0, BakeProgress(0f, 0.1f))
        // A tenth in a second: the next part, up to 0.4, is filled at that pace.
        val a = run(2.0, BakeProgress(0.1f, 0.4f))
        val b = run(2.5, BakeProgress(0.1f, 0.4f))
        val c = run(3.0, BakeProgress(0.1f, 0.4f))
        assertThat(b - a).isCloseTo(0.05f, 0.002f)
        assertThat(c - b).isCloseTo(0.05f, 0.002f)
        // It stops at that part's end until the bake has come further.
        assertThat(run(8.0, BakeProgress(0.1f, 0.4f))).isBetween(0.39f, 0.4f)
    }

    @Test
    fun `before the first part is done it fills slowly, never past that part`() {
        val early = run(0.75, BakeProgress(0f, 0.1f))
        assertThat(early).isGreaterThan(0.02f)
        assertThat(early).isLessThan(0.05f)
        assertThat(run(10.0, BakeProgress(0f, 0.1f))).isLessThanOrEqualTo(0.1f)
    }

    @Test
    fun `it never goes back, not even when a bake starts over`() {
        run(1.0, BakeProgress(0f, 0.2f))
        val before = run(4.0, BakeProgress(0.5f, 0.6f))
        assertThat(run(6.0, BakeProgress(0f, 0.1f))).isGreaterThanOrEqualTo(before)
    }

    @Test
    fun `once the picture is ready it runs to its end in a moment`() {
        run(2.0, BakeProgress(0.3f, 0.4f))
        assertThat(run(2.4, BakeProgress.DONE, ready = true)).isGreaterThan(0.97f)
    }
}
