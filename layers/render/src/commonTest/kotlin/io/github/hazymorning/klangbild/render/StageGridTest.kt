package io.github.hazymorning.klangbild.render

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test

/** The stage's grid for the single cover, Cover Flow and the cover edge to edge (dp at 960 × 540). */
class StageGridTest {
    private val single = StageGrid(ScreenSpec.TV_1080)
    private val edge = StageGrid(ScreenSpec.TV_1080, edge = true)

    @Test
    fun `edge to edge, the cover reaches past the left, the top and the bottom, square and uncut`() {
        val r = edge.artRect
        assertThat(r.left).isCloseTo(-StageGrid.BLEED, 0.01f)
        assertThat(r.top).isCloseTo(-StageGrid.BLEED, 0.01f)
        assertThat(r.bottom).isCloseTo(540f + StageGrid.BLEED, 0.01f)
        assertThat(r.width).isCloseTo(r.height, 0.01f)
        assertThat(edge.radius).isEqualTo(0f)
        // Farther than the screen drifts against burn-in (3 dp).
        assertThat(StageGrid.BLEED).isGreaterThan(3f)
    }

    @Test
    fun `edge to edge, the column stands in the middle of what the cover leaves`() {
        val left = edge.columnLeft - edge.artRect.right
        val right = 960f - (edge.columnLeft + edge.columnWidth)
        assertThat(left).isCloseTo(right, 0.01f)
        assertThat(edge.textCenter).isCloseTo(270f, 0.01f)
    }

    @Test
    fun `edge to edge, the column's frame and the queue's small covers are as beside the single cover`() {
        assertThat(edge.statusTop).isCloseTo(single.statusTop, 0.01f)
        assertThat(edge.footerBottom).isCloseTo(single.footerBottom, 0.01f)
        assertThat(edge.rowCover).isCloseTo(single.rowCover, 0.01f)
        assertThat(edge.columnArt).isCloseTo(single.art, 0.01f)
    }

    @Test
    fun `Cover Flow takes the cover edge to edge for its own grid`() {
        assertThat(StageGrid(ScreenSpec.TV_1080, flow = true, edge = true).artRect).isEqualTo(StageGrid(ScreenSpec.TV_1080, flow = true).artRect)
    }

    @Test
    fun `every cover stands on whole pixels at 1080p`() {
        for (grid in listOf(single, edge, StageGrid(ScreenSpec.TV_1080, flow = true))) {
            val r = grid.artRect
            for (v in listOf(r.left, r.top, r.width)) assertThat(abs(v * 2f - round(v * 2f)), name = "$grid").isLessThan(0.001f)
        }
    }
}
