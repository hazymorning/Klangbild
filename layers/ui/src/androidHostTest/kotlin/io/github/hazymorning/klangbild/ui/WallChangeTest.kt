package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * At sunset the stage turns from paper to the dark wall in one cross-fade, its lines with it: a
 * few frames in they are still the dark lines on paper, and once the wall is dark they are the
 * light ones. The same goes for the status line beside a single cover and in Cover Flow.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class WallChangeTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun singleCoverLinesCrossFadeWithTheWall() {
        val track = Fixtures.track(SyntheticCover.TERRACOTTA)
        var stage by mutableStateOf(StageData(track, Fixtures.scene(track, mdpi, Appearance.LIGHT)))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, PlayerStatus.PLAYING, group = "Wohnzimmer") }
        sunset(column = Region(520, 80, 880, 470)) { stage = StageData(track, Fixtures.scene(track, mdpi, Appearance.DARK)) }
    }

    @Test
    fun coverFlowStatusLineCrossFadesWithTheWall() {
        val covers = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.NOCTURNE, SyntheticCover.MOSS)
        fun flow(wall: Appearance) = Fixtures.flow(
            covers.mapIndexed { i, c -> i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, appearance = wall, flow = true) }.toMap(),
            listOf(0L, 1L, 2L),
            1L,
        )
        var strip by mutableStateOf(flow(Appearance.LIGHT))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheaterOf(strip) }
        // The status line: the topmost line of ink in the text column.
        sunset(region = { b -> topLine(b, 560, 940) }) { strip = flow(Appearance.DARK) }
    }

    private fun sunset(column: Region, change: () -> Unit) = sunset({ column }, change)

    private fun sunset(region: (Bitmap) -> Region, change: () -> Unit) {
        compose.mainClock.advanceTimeBy(3_000)
        val before = capture()
        val column = region(before)
        change()
        compose.mainClock.advanceTimeBy(96)
        val early = capture()
        // Three frames in, the lines are still the dark ink on the paper, nearly as dark as before.
        assertThat(column.darkest(early)).isCloseTo(column.darkest(before), 18f)
        assertThat(column.lightest(early)).isLessThan(column.paper(early) + 12f)
        compose.mainClock.advanceTimeBy(3_000)
        val after = capture()
        // The wall is dark and the lines are the light ink on it.
        assertThat(column.paper(after)).isLessThan(30f)
        assertThat(column.lightest(after)).isGreaterThan(150f)
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** The band of rows (from the top) holding the first line of dark ink between [x0] and [x1]. */
    private fun topLine(b: Bitmap, x0: Int, x1: Int): Region {
        fun inked(y: Int): Boolean {
            val px = IntArray(x1 - x0).also { b.getPixels(it, 0, x1 - x0, x0, y, x1 - x0, 1) }
            return px.any { (it shr 8 and 0xFF) < 120 }
        }
        val first = (40 until b.height).first { inked(it) }
        val last = (first until b.height).first { !inked(it) }
        return Region(x0, first - 3, x1, last + 3)
    }

    /** A part of the screen, and the brightness (0..255) of its darkest, its middle and its lightest pixels. */
    private class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        private fun values(b: Bitmap): List<Int> {
            val w = x1 - x0
            val px = IntArray(w * (y1 - y0)).also { b.getPixels(it, 0, w, x0, y0, w, y1 - y0) }
            return px.map { (0.2126 * (it shr 16 and 0xFF) + 0.7152 * (it shr 8 and 0xFF) + 0.0722 * (it and 0xFF)).toInt() }.sorted()
        }

        /** The mean of the darkest 1% (the ink on paper). */
        fun darkest(b: Bitmap) = values(b).let { v -> v.take(maxOf(1, v.size / 100)).average().toFloat() }

        /** The mean of the lightest 1% (the ink on the dark wall). */
        fun lightest(b: Bitmap) = values(b).let { v -> v.takeLast(maxOf(1, v.size / 100)).average().toFloat() }

        /** The median: the wall itself. */
        fun paper(b: Bitmap) = values(b).let { v -> v[v.size / 2].toFloat() }
    }
}
