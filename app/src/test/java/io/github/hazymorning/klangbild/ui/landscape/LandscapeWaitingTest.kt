package io.github.hazymorning.klangbild.ui.landscape

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.ui.QuietColors
import io.github.hazymorning.klangbild.ui.composeRule
import io.github.hazymorning.klangbild.landscape.PaperMotif
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sign that the landscape is on its way: the far range's skyline, a thin line in the very
 * middle of the screen in the quiet ink, measured on rendered pixels. It comes only when the
 * first picture keeps one waiting, is traced from the left as the paper is cut and never goes
 * back, and is gone before the picture is up.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeWaitingTest {
    @get:Rule
    val compose = composeRule()

    private val ink = brightness(QuietColors.of().secondary.toArgb())

    @Test
    fun theSkylineStandsInTheMiddleInQuietInk() {
        compose.setContent { Fixtures.Still { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready = false, progress = { 0.6f }) } } }
        compose.onRoot().captureRoboImage("build/screenshots/landscape/waiting.png")
        val bmp = capture()
        val (x0, y0, x1, y1) = bounds(bmp) { it > 0 }
        // 200 dp from the first mountain's foot to the last one's, as tall as the range, the line 2.5 dp: at 2 px a dp.
        val tall = (PaperMotif.FAR_FOOT - (-80..1060).minOf { PaperMotif.skyline(it.toFloat()) }) * 200f / 1140f
        assertThat((x1 - x0).toFloat()).isWithin(3f).of(2 * (200f + 2.5f))
        assertThat((y1 - y0).toFloat()).isWithin(4f).of(2 * (tall + 2.5f))
        assertThat((x0 + x1) / 2f).isWithin(1f).of(bmp.width / 2f)
        assertThat((y0 + y1) / 2f).isWithin(1f).of(bmp.height / 2f)
        // The line in the rest screen's quiet ink, never brighter; the part still to come only a trace of it.
        assertThat(brightest(bmp)).isIn(Range.closed(ink - 6, ink + 1))
        val traced = bounds(bmp) { it > ink * 0.6 }
        assertThat((traced[2] - x0).toFloat() / (x1 - x0)).isIn(Range.closed(0.4f, 0.8f))
    }

    @Test
    fun itComesOnlyWhenThePictureKeepsOneWaitingAndGoesBeforeItIsUp() {
        var ready by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready, progress = { 0.5f }) } }
        compose.mainClock.advanceTimeBy(900)
        assertThat(brightest(capture())).isEqualTo(0)
        compose.mainClock.advanceTimeBy(1_000)
        val a = capture()
        assertThat(brightest(a)).isGreaterThan(100)
        // Its head breathes.
        compose.mainClock.advanceTimeBy(800)
        assertThat(a.sameAs(capture())).isFalse()
        // The picture is ready: the line is drawn to its end, and goes while the picture is still coming up.
        ready = true
        compose.mainClock.advanceTimeBy(160)
        val end = capture()
        assertThat(bounds(end) { it > ink * 0.6 }[2]).isGreaterThan(bounds(a) { it > ink * 0.6 }[2])
        compose.mainClock.advanceTimeBy(500)
        assertThat(brightest(capture())).isEqualTo(0)
    }

    @Test
    fun theLineFollowsTheBakeAndNeverGoesBack() {
        var baked by mutableFloatStateOf(0.2f)
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready = false, progress = { baked }) } }
        compose.mainClock.advanceTimeBy(3_000)
        val early = bounds(capture()) { it > ink * 0.6 }[2]
        baked = 0.7f
        compose.mainClock.advanceTimeBy(2_000)
        val later = bounds(capture()) { it > ink * 0.6 }[2]
        assertThat(later).isGreaterThan(early + 100)
        // A new bake starting over never takes the line back.
        baked = 0.1f
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(bounds(capture()) { it > ink * 0.6 }[2]).isEqualTo(later)
    }

    @Test
    fun aPictureThatComesQuicklyComesWithoutIt() {
        var ready by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready, progress = { 0.9f }) } }
        compose.mainClock.advanceTimeBy(600)
        ready = true
        repeat(10) {
            compose.mainClock.advanceTimeBy(200)
            assertThat(brightest(capture())).isEqualTo(0)
        }
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun brightness(p: Int) = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    private fun brightest(bmp: Bitmap): Int {
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return px.maxOf { brightness(it) }
    }

    /** The box around the pixels whose brightness passes [lit]: left, top, right, bottom (exclusive). */
    private fun bounds(bmp: Bitmap, lit: (Int) -> Boolean): IntArray {
        var x0 = bmp.width
        var y0 = bmp.height
        var x1 = 0
        var y1 = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (!lit(brightness(bmp.getPixel(x, y)))) continue
            x0 = minOf(x0, x)
            y0 = minOf(y0, y)
            x1 = maxOf(x1, x + 1)
            y1 = maxOf(y1, y + 1)
        }
        return intArrayOf(x0, y0, x1, y1)
    }
}
