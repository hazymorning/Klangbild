package io.github.hazymorning.klangbild.landscape

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import kotlin.math.abs
import kotlin.test.Test

/**
 * The camera's journey, worked out over hours: it stays where the paper reaches (with the
 * screen's drift on top), keeps the near bank under the label, never jumps, and moves enough to
 * be seen. Within any two minutes it goes in or out and across by a good stretch.
 */
class LandscapeCameraJourneyTest {
    private val m = PaperMotif
    private val c = LandscapeCamera

    private fun poses(side: LabelSide) = (0 until 6 * 3_600 * 4).asSequence().map { i -> i / 4.0 to c.at(i / 4.0, side) }

    @Test
    fun `every layer covers the screen and its drift`() {
        for (side in LabelSide.entries) for ((t, p) in poses(side)) {
            assertThat(p.zoom).isGreaterThanOrEqualTo(c.ZOOM_MIN - 1e-4f)
            assertThat(p.zoom).isLessThanOrEqualTo(c.ZOOM_MAX + 1e-4f)
            for (d in Depth.entries) {
                val k = p.scale(d)
                fun x(v: Float) = c.PIVOT_X + (v - c.PIVOT_X) * k + p.dx(d)
                fun y(v: Float) = c.PIVOT_Y + (v - c.PIVOT_Y) * k + p.dy(d)
                val eps = 0.01f
                assertThat(x(-m.OVERSCAN), name = "$side, $d at $t s: left").isLessThanOrEqualTo(-c.DRIFT + eps)
                assertThat(x(m.WIDTH + m.OVERSCAN), name = "$side, $d at $t s: right").isGreaterThanOrEqualTo(m.WIDTH + c.DRIFT - eps)
                assertThat(y(-m.OVERSCAN), name = "$side, $d at $t s: top").isLessThanOrEqualTo(-c.DRIFT + eps)
                assertThat(y(m.HEIGHT + m.OVERSCAN), name = "$side, $d at $t s: bottom").isGreaterThanOrEqualTo(m.HEIGHT + c.DRIFT - eps)
            }
        }
    }

    @Test
    fun `the near bank always lies under the label, which stands still`() {
        for (side in listOf(LabelSide.LEFT, LabelSide.RIGHT)) {
            val bank = Shapes.line(if (side == LabelSide.LEFT) m.foreground(side).frontLeft else m.foreground(side).frontRight)
            val left = if (side == LabelSide.LEFT) m.LABEL_LEFT_X else m.LABEL_RIGHT_X
            val top = m.LABEL_BOTTOM - m.LABEL_HEIGHT
            for ((t, p) in poses(side)) {
                val k = p.scale(Depth.FRONT)
                // Every point of the label's top edge and its ends, taken back into the moved near bank.
                var x = left - c.BANK_MARGIN + 0.5f
                while (x <= left + m.LABEL_MAX_WIDTH + c.BANK_MARGIN - 0.5f) {
                    for (y in listOf(top - c.BANK_MARGIN + 0.5f, m.LABEL_BOTTOM)) {
                        val bx = c.PIVOT_X + (x - c.PIVOT_X - p.dx(Depth.FRONT)) / k
                        val by = c.PIVOT_Y + (y - c.PIVOT_Y - p.dy(Depth.FRONT)) / k
                        assertThat(by, name = "$side at $t s: label point $x/$y over the bank's edge").isGreaterThanOrEqualTo(bank.yAt(bx))
                    }
                    x += 4f
                }
            }
        }
    }

    @Test
    fun `it glides, never jumps`() {
        for (side in LabelSide.entries) {
            var prev: CameraPose? = null
            for ((t, p) in poses(side)) {
                prev?.let { q ->
                    // A quarter of a second: a dp or so across (at most 6 dp a second), a hair of zoom.
                    assertThat(abs(p.panX - q.panX), name = "$side at $t s: across").isLessThan(1.5f)
                    assertThat(abs(p.panY - q.panY), name = "$side at $t s: up and down").isLessThan(0.8f)
                    assertThat(abs(p.zoom - q.zoom), name = "$side at $t s: zoom").isLessThan(0.0015f)
                }
                prev = p
            }
        }
    }

    @Test
    fun `within any two minutes it moves enough to be seen`() {
        for (side in LabelSide.entries) {
            val all = poses(side).map { it.second }.toList()
            val window = 2 * 60 * 4
            var i = 0
            while (i + window < all.size) {
                val part = all.subList(i, i + window)
                val zoom = part.maxOf { it.zoom } - part.minOf { it.zoom }
                val across = part.maxOf { it.panX } - part.minOf { it.panX }
                // At the screen's edge a zoom of 0.03 moves the picture by 15 dp.
                assertThat(zoom * c.PIVOT_X + across, name = "$side from ${i / 4} s: zoom $zoom, across $across").isGreaterThanOrEqualTo(30f)
                i += 60
            }
        }
    }
}
