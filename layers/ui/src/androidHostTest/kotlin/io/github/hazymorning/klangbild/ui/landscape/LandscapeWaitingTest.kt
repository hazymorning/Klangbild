package io.github.hazymorning.klangbild.ui.landscape

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.landscape.BakeProgress
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.ui.QuietColors
import io.github.hazymorning.klangbild.ui.composeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The sign that the landscape is on its way: a line of words over a bar like the stage's, the two
 * in the very middle of the screen, measured on rendered pixels (2 px a dp). It comes only when
 * the first picture keeps one waiting, fills as the paper is cut and never goes back, and is gone
 * before the picture is up.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeWaitingTest {
    @get:Rule
    val compose = composeRule()

    private val colors = QuietColors.of()
    private val words = brightness(colors.secondary.toArgb())
    private val fill = brightness(colors.primary.toArgb())

    @Test
    fun theWordsAndTheBarStandTogetherInTheMiddle() {
        val bmp = still(TestTexts.german, "waiting")
        val bar = barBounds(bmp)
        // 240 dp by 4 dp, centered across.
        assertThat((bar[2] - bar[0]).toFloat()).isCloseTo(480f, 2f)
        assertThat((bar[3] - bar[1]).toFloat()).isCloseTo(8f, 1f)
        assertThat((bar[0] + bar[2]) / 2f).isCloseTo(bmp.width / 2f, 1f)
        // The words' baseline (German has no descenders here) 16 dp above the bar; from their cap
        // height (17 sp × 0.70 above it) to the bar's lower edge, the two stand in the very middle.
        val text = bounds(bmp, bottom = bar[1]) { it > 40 }
        assertThat((bar[1] - text[3]).toFloat()).isCloseTo(32f, 1f)
        val capTop = text[3] - 2 * 17f * 0.70f
        assertThat((capTop + bar[3]) / 2f).isCloseTo(bmp.height / 2f, 1f)
        // The words centered on their ink, in the rest screen's quiet ink; the bar's fill a level brighter.
        assertThat((text[0] + text[2]) / 2f).isCloseTo(bmp.width / 2f, 1f)
        assertThat(brightest(bmp, bottom = bar[1])).isBetween(words - 6, words + 1)
        assertThat(brightest(bmp, top = bar[1])).isBetween(fill - 2, fill + 1)
        // Filled to where the bake has come.
        assertThat((filledTo(bmp, bar) - bar[0]).toFloat() / (bar[2] - bar[0])).isCloseTo(0.6f, 0.01f)
    }

    @Test
    fun inEnglishTheWordsAreCenteredTheSameWay() {
        val bmp = still(TestTexts.english(), "waiting_en")
        val bar = barBounds(bmp)
        val text = bounds(bmp, bottom = bar[1]) { it > 40 }
        assertThat((text[0] + text[2]) / 2f).isCloseTo(bmp.width / 2f, 1f)
        assertThat((bar[0] + bar[2]) / 2f).isCloseTo(bmp.width / 2f, 1f)
    }

    @Test
    fun itComesOnlyWhenThePictureKeepsOneWaitingAndGoesBeforeItIsUp() {
        var ready by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Glyphs { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready, progress = { BakeProgress(0.5f, 0.6f) }) } } }
        compose.mainClock.advanceTimeBy(900)
        assertThat(brightest(capture())).isEqualTo(0)
        compose.mainClock.advanceTimeBy(1_000)
        val a = capture()
        assertThat(brightest(a)).isGreaterThan(100)
        // The picture is ready: the bar runs to its end, and the sign goes while the picture is still coming up.
        ready = true
        compose.mainClock.advanceTimeBy(160)
        val end = capture()
        assertThat(filledTo(end, barBounds(end))).isGreaterThan(filledTo(a, barBounds(a)))
        compose.mainClock.advanceTimeBy(500)
        assertThat(brightest(capture())).isEqualTo(0)
    }

    @Test
    fun theBarFollowsTheBakeAndNeverGoesBack() {
        var baked by mutableStateOf(BakeProgress(0.2f, 0.3f))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Glyphs { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready = false, progress = { baked }) } } }
        compose.mainClock.advanceTimeBy(3_000)
        val first = capture()
        val bar = barBounds(first)
        val early = filledTo(first, bar)
        baked = BakeProgress(0.7f, 0.8f)
        compose.mainClock.advanceTimeBy(2_000)
        val later = filledTo(capture(), bar)
        assertThat(later).isGreaterThan(early + 100)
        // A new bake starting over never takes the bar back.
        baked = BakeProgress(0.1f, 0.2f)
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(filledTo(capture(), bar)).isGreaterThanOrEqualTo(later)
    }

    @Test
    fun aPictureThatComesQuicklyComesWithoutIt() {
        var ready by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Glyphs { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready, progress = { BakeProgress(0.9f, 1f) }) } } }
        compose.mainClock.advanceTimeBy(600)
        ready = true
        repeat(10) {
            compose.mainClock.advanceTimeBy(200)
            assertThat(brightest(capture())).isEqualTo(0)
        }
    }

    private fun still(t: Texts, name: String): Bitmap {
        compose.setContent {
            Fixtures.Still(t) { Box(Modifier.fillMaxSize().background(Color.Black)) { LandscapeWaiting(ready = false, progress = { BakeProgress(0.6f, 0.7f) }) } }
        }
        compose.onRoot().captureRoboImage("build/screenshots/landscape/$name.png")
        return capture()
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun brightness(p: Int) = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    /** The brightest pixel between the rows [top] (inclusive) and [bottom] (exclusive). */
    private fun brightest(bmp: Bitmap, top: Int = 0, bottom: Int = bmp.height): Int {
        var most = 0
        for (y in top until bottom) for (x in 0 until bmp.width) most = maxOf(most, brightness(bmp.getPixel(x, y)))
        return most
    }

    /** The bar: the lowest rows with anything lit, as far as the faint track reaches. */
    private fun barBounds(bmp: Bitmap): IntArray {
        val lit = bounds(bmp) { it > 12 }
        var top = lit[3] - 1
        while (top > 0 && (0 until bmp.width).any { brightness(bmp.getPixel(it, top - 1)) > 12 }) top--
        return bounds(bmp, top = top) { it > 12 }
    }

    /** Where the bar's fill ends (px): the last column on its middle row lit at least half as much as the fill. */
    private fun filledTo(bmp: Bitmap, bar: IntArray): Int {
        val y = (bar[1] + bar[3]) / 2
        return (bar[0] until bar[2]).lastOrNull { brightness(bmp.getPixel(it, y)) > fill / 2 }?.plus(1) ?: bar[0]
    }

    /** The box around the pixels whose brightness passes [lit] between the rows [top] and [bottom]: left, top, right, bottom (exclusive). */
    private fun bounds(bmp: Bitmap, top: Int = 0, bottom: Int = bmp.height, lit: (Int) -> Boolean): IntArray {
        var x0 = bmp.width
        var y0 = bmp.height
        var x1 = 0
        var y1 = 0
        for (y in top until bottom) for (x in 0 until bmp.width) {
            if (!lit(brightness(bmp.getPixel(x, y)))) continue
            x0 = minOf(x0, x)
            y0 = minOf(y0, y)
            x1 = maxOf(x1, x + 1)
            y1 = maxOf(y1, y + 1)
        }
        return intArrayOf(x0, y0, x1, y1)
    }
}
