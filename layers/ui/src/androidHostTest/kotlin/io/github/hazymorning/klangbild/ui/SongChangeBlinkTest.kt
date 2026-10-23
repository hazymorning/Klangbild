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
import assertk.assertions.isGreaterThanOrEqualTo
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
 * A song change never blinks: the old lines leave from where they stand, whenever in a frame the
 * change arrives, even between composing a frame and drawing it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class SongChangeBlinkTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)
    private val covers = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.NOCTURNE, SyntheticCover.MOSS, SyntheticCover.PASTEL)

    @Test
    fun singleCoverNeverBlinks() {
        val stages = covers.map { c -> Fixtures.track(c).let { StageData(it, Fixtures.scene(it, mdpi, Appearance.DARK)) } }
        var stage by mutableStateOf(stages[0])
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, PlayerStatus.PLAYING, group = "Wohnzimmer") }
        changes { k -> stage = stages[k % stages.size] }
    }

    @Test
    fun coverFlowNeverBlinks() {
        val scenes = covers.mapIndexed { i, c -> i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, flow = true) }.toMap()
        val ids = scenes.keys.toList()
        var strip by mutableStateOf(Fixtures.flow(scenes, ids, ids[1]))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheaterOf(strip) }
        changes { k -> strip = Fixtures.flow(scenes, ids, ids[1 + k % 2]) }
    }

    /** Song changes arriving at every point of a frame: the column never loses its lines at once. */
    private fun changes(change: (Int) -> Unit) {
        compose.mainClock.advanceTimeBy(3_000)
        var k = 1
        for (offset in 0 until 16 step 2) {
            val steady = inked(capture())
            compose.mainClock.advanceTimeBy(offset.toLong())
            change(k++)
            for (step in 0 until 4) {
                compose.mainClock.advanceTimeBy(16)
                // The old lines leave over 220 ms and the new ones rise later, so within these
                // frames the column holds at least most of what it held.
                assertThat(inked(capture())).isGreaterThanOrEqualTo(steady * 0.6f)
            }
            compose.mainClock.advanceTimeBy(3_000)
        }
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** How much ink the text column holds: its pixels brighter than the dark wall's text shade. */
    private fun inked(b: Bitmap): Float {
        val x0 = 520
        val y0 = 80
        val w = 940 - x0
        val h = 470 - y0
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, x0, y0, w, h) }
        return px.count { (it shr 8 and 0xFF) > 110 }.toFloat()
    }
}
