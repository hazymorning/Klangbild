package io.github.hazymorning.klangbild.render

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.domain.StageView
import org.junit.Test

/** The cover flow's geometry against the design's measurements (dp at 960 × 540). */
class FlowGeometryTest {
    private val layout = StageGrid(ScreenSpec.TV_1080, StageView.FLOW)
    private val g = FlowGeometry(layout)
    private val c = FloatArray(8)

    @Test
    fun `the middle card faces the room at its place in the grid`() {
        g.corners(0f, c)
        assertThat(c[0]).isWithin(0.01f).of(layout.artRect.left)
        assertThat(c[1]).isWithin(0.01f).of(layout.artRect.top)
        assertThat(c[2] - c[0]).isWithin(0.01f).of(300f)
        // the text column starts 145 dp to the right of it and is 314 dp wide
        assertThat(layout.columnLeft).isWithin(0.01f).of(598f)
        assertThat(layout.columnWidth).isWithin(0.01f).of(314f)
    }

    @Test
    fun `the strip and the column stand in the middle of the screen`() {
        // As much room left of the strip's outermost card as right of the column …
        g.corners(-2f, c)
        val right = layout.screen.widthDp - (layout.columnLeft + layout.columnWidth)
        assertThat(c[0]).isWithin(1f).of(right)
        // … and the middle card as far from the top as from the bottom.
        g.corners(0f, c)
        assertThat(c[1]).isWithin(0.01f).of(layout.screen.heightDp - c[1] - g.side)
    }

    @Test
    fun `the side cards stand where the design has them`() {
        for ((p, edge) in listOf(1f to 222.5f, 2f to 255f, -1f to -222.5f, -2f to -255f)) {
            g.corners(p, c)
            // the outer edge: right for the cards to come, left for those played
            val x = if (p > 0) c[2] else c[0]
            val top = if (p > 0) c[3] else c[1]
            val bottom = if (p > 0) c[5] else c[7]
            assertThat(x - g.cx).isWithin(1f).of(edge)
            // standing at 92 % of the middle card's height (the reflection below excluded)
            val cardBottom = top + (bottom - top) * g.side / g.height
            assertThat((cardBottom - top) / g.side).isWithin(0.01f).of(0.923f)
            // the outer edge is nearer than the inner one: taller
            val inner = if (p > 0) c[7] - c[1] else c[5] - c[3]
            assertThat(bottom - top).isGreaterThan(inner)
        }
    }

    @Test
    fun `the side cards hide behind the middle one, the cards to come stay clear of the text`() {
        g.corners(1f, c)
        assertThat(c[0]).isLessThan(layout.artRect.right)
        g.corners(2f, c)
        assertThat(c[2]).isLessThan(layout.columnLeft - 30f)
        assertThat(g.presence(2f)).isEqualTo(1f)
        assertThat(g.presence(3f)).isEqualTo(0f)
    }

    @Test
    fun `cards that pass each other never cut through one another`() {
        // The leaving middle card against the next one (a step), the first card of a train
        // (a jump), and the history stepping aside; mirrored for a step back.
        val moves = listOf(
            (0f to -1f) to (1f to 0f),
            (0f to -1f) to (FlowGeometry.EDGE to 0f),
            (-1f to -2f) to (0f to -1f),
            (0f to -1f) to (FlowGeometry.EDGE to 1f),
        )
        val a = FloatArray(8)
        val b = FloatArray(8)
        for ((m1, m2) in moves) for (sign in listOf(1f, -1f)) {
            for (i in 0..200) {
                val s = i / 200f
                val p1 = sign * (m1.first + (m1.second - m1.first) * s)
                val p2 = sign * (m2.first + (m2.second - m2.first) * s)
                g.corners(p1, a)
                g.corners(p2, b)
                val left = maxOf(a[0], b[0])
                val right = minOf(a[2], b[2])
                if (right - left <= 0.5f) continue
                // Where the two overlap on screen, the one nearer the middle is in front all along:
                // the painter's order (by distance from the middle) is the true one.
                assertThat(kotlin.math.abs(p1)).isNotEqualTo(kotlin.math.abs(p2))
                val (front, back) = if (kotlin.math.abs(p1) < kotlin.math.abs(p2)) a to b else b to a
                for (x in listOf(left, right)) {
                    assertThat(nearness(front, x)).isAtLeast(nearness(back, x) - 1e-4f)
                }
            }
        }
    }

    /** How near (the perspective's scale) the card with [corners] is at screen [x]: linear on screen. */
    private fun nearness(corners: FloatArray, x: Float): Float {
        val k0 = (g.cy - corners[1]) / (g.side / 2f)
        val k1 = (g.cy - corners[3]) / (g.side / 2f)
        return k0 + (k1 - k0) * (x - corners[0]) / (corners[2] - corners[0])
    }

    @Test
    fun `with cards missing, what is there stands with the text in the middle of the screen`() {
        val half = g.side / 2f
        for (left in 0..2) for (right in 0..2) {
            val b = g.balance(left, right)
            // The outermost card on each side, or the middle one where none stands beside it.
            fun edge(p: Float, outer: Int): Float {
                g.corners(p, c)
                return c[outer] + b.cards
            }
            val start = if (left == 0) g.cx + b.cards - half else edge(-left.toFloat(), 0)
            val end = if (right == 0) g.cx + b.cards + half else edge(right.toFloat(), 2)
            val column = layout.columnLeft + b.column
            val room = layout.screen.widthDp - (column + layout.columnWidth)
            assertThat(start).isWithin(0.5f).of(room)
            // The column keeps its gap: 40 dp after the cards to come, else the single view's 64 dp.
            assertThat(column - end).isWithin(0.5f).of(if (right > 0) 40f else 64f)
        }
        // The full strip stays exactly where the grid has it.
        assertThat(g.balance(2, 2)).isEqualTo(Balance.NONE)
        assertThat(g.balance(2, 0).cards).isWithin(0.5f).of(40.5f)
        assertThat(g.balance(0, 2).cards).isWithin(0.5f).of(-52.5f)
        assertThat(g.leftmostColumn).isWithin(0.5f).of(g.balance(0, 0).column)
        assertThat(g.leftmostColumn).isWithin(0.5f).of(-93f)
    }

    @Test
    fun `positions in between move smoothly`() {
        var last = Float.NaN
        var p = 0f
        while (p <= 3f) {
            g.corners(p, c)
            if (!last.isNaN()) assertThat(c[2] - last).isAtLeast(-0.001f)
            last = c[2]
            p += 0.05f
        }
    }
}
