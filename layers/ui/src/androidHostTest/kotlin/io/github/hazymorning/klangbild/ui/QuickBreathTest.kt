package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test

/**
 * While the queue display just chosen is pending, the Queue value in the quick settings breathes.
 * The word fades into a muted value's ink and back at the same pace for as long as the wait
 * lasts, and when the wait ends it returns to exactly the pixels it had.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class QuickBreathTest {
    @get:Rule
    val compose = composeRule()

    private var pending by mutableStateOf(false)
    private var appearance by mutableStateOf(Appearance.DARK)

    /** The queue's entry is muted, as a value with nothing to show: the ink the breath sinks into. */
    private var muted by mutableStateOf(false)

    private val rows = QuickMenu.rows(Settings(), QuickContext(music = true, pending = QueueDisplay.FLOW), Fixtures.texts)
    private val queue = rows.indexOfFirst { it.item == QuickItem.QUEUE }

    /** Lets whatever is made off the main thread (the band of light under the chosen entry) come, and the inks settle. */
    private fun settle() = repeat(20) {
        Thread.sleep(20)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    /**
     * Lets [ms] pass frame by frame, each frame's work done before the next, as on the device: the
     * breath's own coroutine goes on between frames, which one long step of the clock would not
     * let it do.
     */
    private fun pass(ms: Int) = repeat(ms / FRAME) {
        compose.mainClock.advanceTimeBy(FRAME.toLong())
        compose.waitForIdle()
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** The value "Cover Flow" in [b]: from a little before the row's left edge to well before the next entry. */
    private fun word(b: Bitmap): IntArray {
        val x0 = QuickGrid.LEFT.toInt() - 4
        val y0 = (QuickGrid.VALUE_BASELINE - 22f).toInt()
        return IntArray(WORD_W * WORD_H).also { b.getPixels(it, 0, WORD_W, x0, y0, WORD_W, WORD_H) }
    }

    /** The largest difference of a channel between [a] and [b] where the value stands (0 to 255). */
    private fun apart(a: Bitmap, b: Bitmap): Int {
        val p = word(a)
        val q = word(b)
        return p.indices.maxOf { i -> (0..16 step 8).maxOf { s -> abs((p[i] shr s and 0xFF) - (q[i] shr s and 0xFF)) } }
    }

    @Test
    fun theQueueValueSinksIntoAMutedInkAndComesBackAsItWas() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val shown = if (muted) rows.mapIndexed { i, r -> if (i == queue) r.copy(muted = true) else r } else rows
            QuickBar(QuickState(shown, queue), appearance, pending = pending)
        }
        for (wall in Appearance.entries) {
            appearance = wall
            muted = true
            settle()
            val quiet = capture()
            muted = false
            settle()
            val before = capture()
            pending = true
            // At the bottom of the breath the word stands in a muted value's ink, well apart from its own.
            pass(Motion.BREATH_MS + 50)
            val bottom = capture()
            assertThat(apart(bottom, quiet), name = "$wall at the bottom").isLessThanOrEqualTo(2)
            assertThat(apart(bottom, before), name = "$wall the breath's depth").isGreaterThan(40)
            // As slowly, it comes back to its own ink, and goes on breathing.
            pass(Motion.BREATH_MS)
            assertThat(apart(capture(), before), name = "$wall at the top").isLessThanOrEqualTo(2)
            pass(Motion.BREATH_MS / 2)
            assertThat(apart(capture(), before), name = "$wall sinking again").isGreaterThan(10)
            // The wait over halfway down, the word glides back to exactly the pixels it had.
            pending = false
            pass(Motion.PAUSE_MS + 100)
            assertThat(apart(capture(), before), name = "$wall after the wait").isEqualTo(0)
        }
    }

    /**
     * The bottom of the breath over a still stage: the queue's entry chosen on both walls, at
     * rest beside the chosen one, and the menu's Queue line.
     */
    @Category(Pictures::class)
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    @Test
    fun pictures() {
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        var menu by mutableStateOf(false)
        var cursor by mutableIntStateOf(queue)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(track, next = Fixtures.spiegel, appearance = appearance))
                    // The stage stands still; only the quick settings or the menu breathe.
                    CompositionLocalProvider(LocalStill provides false) {
                        if (menu) {
                            MenuScreen(Fixtures.menu(MenuEntry.QUEUE, Settings(queue = QueueDisplay.FLOW)), pending = pending)
                        } else {
                            QuickBar(QuickState(rows, cursor), appearance, pending = pending)
                        }
                    }
                }
            }
        }
        for ((shot, wall, inMenu, at) in listOf(
            Shot("dark", Appearance.DARK, false, queue),
            Shot("paper", Appearance.LIGHT, false, queue),
            Shot("rest", Appearance.DARK, false, queue + 1),
            Shot("menu", Appearance.DARK, true, queue),
        )) {
            pending = false
            appearance = wall
            menu = inMenu
            cursor = at
            settle()
            pending = true
            pass(Motion.BREATH_MS + 50)
            compose.onRoot().captureRoboImage("build/screenshots/quick_breath/$shot.png")
        }
    }

    private data class Shot(val name: String, val wall: Appearance, val menu: Boolean, val cursor: Int)

    private companion object {
        /** A frame of the test's clock (ms). */
        const val FRAME = 16

        /** The value's place (px at mdpi): the word "Cover Flow" with room around it. */
        const val WORD_W = 150
        const val WORD_H = 30
    }
}
