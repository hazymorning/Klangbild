package io.github.hazymorning.klangbild.landscape

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaperMotifTest {
    @Test
    fun `the wide bank keeps clear of the label on its side`() {
        for (side in listOf(LabelSide.LEFT, LabelSide.RIGHT)) {
            val fg = PaperMotif.foreground(side)
            val line = Shapes.line(if (side == LabelSide.LEFT) fg.frontLeft else fg.frontRight)
            val left = if (side == LabelSide.LEFT) PaperMotif.LABEL_LEFT_X else PaperMotif.LABEL_RIGHT_X
            val top = PaperMotif.LABEL_BOTTOM - PaperMotif.LABEL_HEIGHT
            var x = left
            while (x <= left + PaperMotif.LABEL_MAX_WIDTH) {
                assertThat(line.yAt(x)).isAtMost(top - PaperMotif.LABEL_CLEARANCE)
                x += 1f
            }
        }
    }

    @Test
    fun `the fire burns on the other side of the clearing from the label`() {
        val left = PaperMotif.foreground(LabelSide.LEFT)
        val right = PaperMotif.foreground(LabelSide.RIGHT)
        assertThat(left.fireX - 40f).isGreaterThan(PaperMotif.LABEL_LEFT_X + PaperMotif.LABEL_MAX_WIDTH)
        assertThat(right.fireX + 40f).isLessThan(PaperMotif.LABEL_RIGHT_X)
    }

    @Test
    fun `the label keeps its margins from the screen's edges`() {
        assertThat(PaperMotif.LABEL_LEFT_X).isAtLeast(48f)
        assertThat(PaperMotif.LABEL_RIGHT_X + PaperMotif.LABEL_MAX_WIDTH).isAtMost(PaperMotif.WIDTH - 48f)
        assertThat(PaperMotif.LABEL_BOTTOM).isAtMost(PaperMotif.HEIGHT - 27f)
    }

    @Test
    fun `lines give their height in both directions`() {
        val down = Shapes.line(listOf(Knot(0f, 0f), Knot(100f, 100f)))
        val back = Shapes.line(listOf(Knot(100f, 100f), Knot(0f, 0f)))
        for (x in listOf(-10f, 0f, 25f, 50f, 99f, 120f)) {
            assertThat(back.yAt(x)).isWithin(0.01f).of(down.yAt(x))
        }
        assertThat(back.yAt(30f)).isWithin(0.01f).of(30f)
    }

    @Test
    fun `the near banks stand in front of everything at the bottom`() {
        // Each bank's outline closes beyond the screen's bottom and side.
        for (side in LabelSide.entries) {
            val fg = PaperMotif.foreground(side)
            assertThat(fg.frontLeft.first().x).isLessThan(-PaperMotif.OVERSCAN)
            assertThat(fg.frontRight.first().x).isGreaterThan(PaperMotif.WIDTH + PaperMotif.OVERSCAN)
            assertThat(fg.frontLeft.last().y).isGreaterThan(PaperMotif.HEIGHT + PaperMotif.OVERSCAN)
            assertThat(fg.frontRight.last().y).isGreaterThan(PaperMotif.HEIGHT + PaperMotif.OVERSCAN)
        }
    }

    @Test
    fun `the far range's skyline is its mountains' upper edge`() {
        // The highest summit is the skyline's highest point; between the mountains, the saddles.
        val highest = PaperMotif.farRange.minOf { it.summitY }
        var top = Float.MAX_VALUE
        var x = -PaperMotif.OVERSCAN
        while (x <= PaperMotif.WIDTH + PaperMotif.OVERSCAN) {
            top = minOf(top, PaperMotif.skyline(x))
            x += 0.5f
        }
        assertThat(top).isWithin(1f).of(highest)
        assertThat(PaperMotif.skyline(352f)).isWithin(1f).of(134f)
    }

    @Test
    fun `the tent and the stones stand where they are seen, clear of the banks, the fire and each other`() {
        for (side in LabelSide.entries) {
            val fg = PaperMotif.foreground(side)
            val left = Shapes.line(fg.frontLeft)
            val right = Shapes.line(fg.frontRight)
            fun hidden(x: Float, y: Float) = (x < PaperMotif.WIDTH / 2 && y > left.yAt(x)) || (x > PaperMotif.WIDTH / 2 && y > right.yAt(x))
            // The tent: its corners and its top in the open, well apart from the fire's stones.
            val foot = fg.fireY - 5f
            for (x in listOf(fg.tentX - PaperBake.TENT_HALF, fg.tentX, fg.tentX + PaperBake.TENT_HALF * 1.45f)) {
                assertThat(hidden(x, foot - 1f)).isFalse()
            }
            assertThat(kotlin.math.abs(fg.tentX - fg.fireX)).isGreaterThan(PaperBake.TENT_HALF + 27f + 12f)
            for (r in fg.rocks) {
                assertThat(hidden(r.x, r.y - r.h * 0.5f)).isFalse()
                assertThat(kotlin.math.abs(r.x - fg.fireX)).isGreaterThan(27f + r.w / 2f + 6f)
            }
            // The tent stands on the fire's side, away from the label.
            if (side == LabelSide.LEFT) assertThat(fg.tentX).isGreaterThan(PaperMotif.LABEL_LEFT_X + PaperMotif.LABEL_MAX_WIDTH)
            if (side == LabelSide.RIGHT) assertThat(fg.tentX).isLessThan(PaperMotif.LABEL_RIGHT_X)
        }
    }
}
