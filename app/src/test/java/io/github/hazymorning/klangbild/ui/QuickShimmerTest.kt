package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.theme.textType
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The quick settings' Queue value shimmers while the queue's way just chosen is on its way: a
 * band of its own ink crosses the word, which waits in a quieter ink, at an even pace; a pass
 * that has begun always ends, and afterwards the word is exactly as it was before, so the change
 * between the plain and the shimmering word never shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class QuickShimmerTest {
    @get:Rule
    val compose = composeRule()

    private var preparing by mutableStateOf(false)

    /** The word's width (px) as it is set. */
    private var wordWidth = 0

    /** The value "Cover Flow" (a choice still waiting), on the queue's entry, chosen. */
    private fun show(appearance: Appearance = Appearance.DARK) {
        val rows = QuickMenu.rows(Settings(), QuickContext(music = true, pending = QueueDisplay.FLOW), Fixtures.texts)
        val state = QuickState(rows, rows.indexOfFirst { it.item == QuickItem.QUEUE })
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val style = textType(QuickGrid.VALUE_SP, QuickGrid.VALUE_WEIGHT)
            wordWidth = rememberTextMeasurer().measure("Cover Flow", style).size.width
            QuickBar(state, appearance, preparing = preparing)
        }
        // The band of light under the chosen entry is made off the main thread first.
        repeat(20) {
            Thread.sleep(20)
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /**
     * Lets [ms] pass frame by frame, each frame's work done before the next, as on the device: the
     * shimmer's own coroutine goes on between frames, which one long step of the clock would not
     * let it do.
     */
    private fun pass(ms: Long) = repeat((ms / FRAME).toInt()) {
        compose.mainClock.advanceTimeBy(FRAME)
        compose.waitForIdle()
    }

    /** The value's place: from a little before the row's left edge to well before the next entry. */
    private val x0 = QuickGrid.LEFT.toInt() - 4
    private val x1 = x0 + 150
    private val y0 = (QuickGrid.VALUE_BASELINE - 22f).toInt()
    private val y1 = (QuickGrid.VALUE_BASELINE + 8f).toInt()

    /** The value's place in [b], as luma (0 to 255) by column, then row. */
    private fun word(b: Bitmap): Array<FloatArray> = Array(x1 - x0) { i ->
        FloatArray(y1 - y0) { j ->
            val p = b.getPixel(x0 + i, y0 + j)
            0.2126f * (p shr 16 and 0xFF) + 0.7152f * (p shr 8 and 0xFF) + 0.0722f * (p and 0xFF)
        }
    }

    /** Whether the value's place holds exactly the same pixels in [a] and [b]. */
    private fun same(a: Bitmap, b: Bitmap): Boolean {
        fun pixels(bmp: Bitmap) = IntArray((x1 - x0) * (y1 - y0)).also { bmp.getPixels(it, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0) }
        return pixels(a).contentEquals(pixels(b))
    }

    /**
     * Where the band's middle stands (px from the word's start) in [mid], with the word in its
     * own ink in [own] and waiting with no band in [quiet]: the shape of the band (its own ink
     * coming back as cos² across 64 px) fitted to how much of its own ink each column has back.
     */
    private fun bandAt(mid: Bitmap, own: Bitmap, quiet: Bitmap): Float {
        val m = word(mid)
        val r = word(own)
        val q = word(quiet)
        val back = FloatArray(m.size)
        val weight = FloatArray(m.size)
        for (i in m.indices) {
            var num = 0f
            var den = 0f
            for (j in m[i].indices) {
                num += m[i][j] - q[i][j]
                den += r[i][j] - q[i][j]
            }
            weight[i] = abs(den)
            back[i] = if (abs(den) > 1f) num / den else 0f
        }
        fun shape(x: Float, center: Float): Float {
            val u = (x - center) / Shimmer.BAND_DP
            return if (abs(u) >= 0.5f) 0f else cos(PI.toFloat() * u).let { it * it }
        }
        val left = QuickGrid.LEFT - x0
        return (-400..((wordWidth + 40) * 10)).map { it / 10f }.minBy { c ->
            back.indices.sumOf { i -> (weight[i] * (back[i] - shape(i - left + 0.5f, c)).let { it * it }).toDouble() }
        }
    }

    /** Where the band's middle should stand (px from the word's start) [ms] into a pass. */
    private fun expected(ms: Float) = Shimmer.bandLeft(ms / Shimmer.PASS_MS, wordWidth.toFloat(), Shimmer.BAND_DP) + Shimmer.BAND_DP / 2

    @Test
    fun aBandOfItsOwnInkCrossesTheWordAndLeavesItAsItWas() {
        show()
        val before = capture()
        preparing = true
        pass(400)
        val early = capture()
        pass(400)
        val late = capture()
        // The pass is over: the word rests in its quieter ink.
        pass(700)
        val quiet = capture()
        assertWithMessage("waiting, the word is quieter").that(same(quiet, before)).isFalse()
        // The band crosses at an even pace, where a pass puts it, begun a few frames after the
        // word was told (the change reaches the shimmer, then the pass takes a frame of its own).
        val first = bandAt(early, before, quiet)
        val second = bandAt(late, before, quiet)
        assertWithMessage("the band's way in 400 ms (word ${wordWidth}px)").that(second - first).isWithin(6f).of(expected(400f) - expected(0f))
        assertWithMessage("the band 400 ms after the word was told").that(first).isIn(com.google.common.collect.Range.closed(expected(400f - START_MS) - 6f, expected(400f) + 6f))
        // Beyond the band's reach the word waits in its quieter ink, untouched.
        val m = word(late)
        val q = word(quiet)
        val left = QuickGrid.LEFT.toInt() - x0
        for (i in m.indices) {
            if (abs(i - left - second) < Shimmer.BAND_DP / 2 + 6) continue
            for (j in m[i].indices) assertWithMessage("column ${i - left}").that(m[i][j]).isEqualTo(q[i][j])
        }
        // A pass that has begun ends, even when the wait is over meanwhile.
        pass(500)
        preparing = false
        pass(400)
        assertWithMessage("the second pass goes on").that(same(capture(), quiet)).isFalse()
        // Once it has ended and the word has come back to its ink, it is exactly as before.
        pass(1_200)
        assertWithMessage("after the shimmer").that(same(capture(), before)).isTrue()
    }

    @Test
    fun aWaitShorterThanAPassStillShowsAWholePass() {
        show()
        val before = capture()
        preparing = true
        pass(200)
        preparing = false
        // Late in the pass the word still waits, with the band on its last letters.
        pass(900)
        assertWithMessage("late in the pass").that(same(capture(), before)).isFalse()
        // Then it has ended without a rest, and the word is back in its ink.
        pass(800)
        assertWithMessage("after it").that(same(capture(), before)).isTrue()
    }

    @Test
    fun aWaitOverDuringTheRestBringsNoSecondPass() {
        show(Appearance.LIGHT)
        val before = capture()
        preparing = true
        pass(1_450)
        preparing = false
        // The rest ends early and the word comes back to its ink; no band crosses it again.
        for (step in 1..6) {
            pass(150)
            if (step >= 3) assertWithMessage("${1_450 + step * 150} ms").that(same(capture(), before)).isTrue()
        }
    }

    /**
     * The shimmer as frames, every 80 ms from the moment the way is chosen, through a pass, its
     * rest and the next pass: the queue's entry chosen on both walls, at rest beside the chosen
     * one, and the menu's Queue line, over a still stage (to be tiled and looked at).
     */
    @Category(Pictures::class)
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    @Test
    fun frames() {
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        val chosen = QuickMenu.rows(Settings(), QuickContext(music = true, pending = QueueDisplay.FLOW), Fixtures.texts)
        var name by mutableStateOf("dark")
        var appearance by mutableStateOf(Appearance.DARK)
        var cursor by mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(track, next = Fixtures.spiegel, appearance = appearance))
                    // The stage stands still; only the quick settings or the menu run.
                    CompositionLocalProvider(LocalStill provides false) {
                        if (name == "menu") {
                            MenuScreen(Fixtures.menu(MenuEntry.QUEUE, Settings(queue = QueueDisplay.FLOW)), preparing = preparing)
                        } else {
                            QuickBar(QuickState(chosen, cursor), appearance, preparing = preparing)
                        }
                    }
                }
            }
        }
        for ((shot, wall, at) in listOf(Triple("dark", Appearance.DARK, 0), Triple("paper", Appearance.LIGHT, 0), Triple("rest", Appearance.DARK, 1), Triple("menu", Appearance.DARK, 0))) {
            preparing = false
            name = shot
            appearance = wall
            cursor = at
            // Whatever was made off the main thread (the band of light, the menu's light) comes first.
            repeat(25) {
                Thread.sleep(20)
                compose.mainClock.advanceTimeBy(100)
            }
            preparing = true
            for (i in 0..FRAMES) {
                compose.onRoot().captureRoboImage("build/screenshots/quick_shimmer/${shot}_%02d.png".format(i))
                pass(FRAME_MS)
            }
        }
    }

    @Test
    fun theShimmeringWordDrawsJustAsThePlainText() {
        val style = textType(QuickGrid.VALUE_SP, QuickGrid.VALUE_WEIGHT)
        val ink = Color(0xFFE3E1DE)
        var swept by mutableStateOf(false)
        var alpha by mutableStateOf(1f)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.offset(QuickGrid.LEFT.dp, 40.dp)) {
                    if (swept) {
                        SweptWord("Cover Flow", style, Sweep(), word = { ink }, ink = { ink }, alpha = { alpha })
                    } else {
                        BasicText("Cover Flow", style = style, color = { ink.copy(alpha = ink.alpha * alpha) }, maxLines = 1)
                    }
                }
            }
        }
        for (a in listOf(1f, 0.55f)) {
            alpha = a
            swept = false
            compose.waitForIdle()
            val plain = capture()
            swept = true
            compose.waitForIdle()
            val shimmering = capture()
            val differing = (0 until plain.height).sumOf { y -> (0 until plain.width).count { x -> plain.getPixel(x, y) != shimmering.getPixel(x, y) } }
            assertWithMessage("alpha $a").that(differing).isEqualTo(0)
        }
    }

    private companion object {
        /**
         * The most a pass may begin after the word is told (ms): the change reaches the shimmer
         * on the next frame, the pass starts on a frame of its own, and its frames on the next.
         */
        const val START_MS = 160f

        /** A frame of the test's clock (ms). */
        const val FRAME = 16L

        /** The frames of the picture sequence, and the time between them. */
        const val FRAMES = 30
        const val FRAME_MS = 80L
    }
}
