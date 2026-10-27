package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
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
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.textType
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * While the queue display just chosen is pending, dots pulse after the Queue value in the quick
 * settings. The labels and the value's word keep exactly their pixels, and when the wait ends the
 * dots fade out and the bar is exactly as it was.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class QuickPendingTest {
    @get:Rule
    val compose = composeRule()

    private var pending by mutableStateOf(false)
    private var appearance by mutableStateOf(Appearance.DARK)

    private val rows = QuickMenu.rows(Settings(), QuickContext(music = true, pending = QueueDisplay.FLOW), Fixtures.texts)
    private val queue = rows.indexOfFirst { it.item == QuickItem.QUEUE }

    /** Lets whatever is made off the main thread (the band of light under the chosen entry) come, and the inks settle. */
    private fun settle() = repeat(20) {
        Thread.sleep(20)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    /** Lets [ms] pass frame by frame, each frame's work done before the next, as on the device. */
    private fun pass(ms: Int) = repeat(ms / FRAME) {
        compose.mainClock.advanceTimeBy(FRAME.toLong())
        compose.waitForIdle()
    }

    /** The columns from [x0] to [x1] where [a] and [b] differ between the rows [y0] and [y1]. */
    private fun changed(a: Pixels, b: Pixels, y0: Int, y1: Int, x0: Int = 0, x1: Int = a.width): List<Int> =
        (x0 until x1).filter { x -> (y0 until y1).any { y -> Pixels.apart(a[x, y], b[x, y]) > 0 } }

    /** The largest difference of a channel between [a] and [b] between the rows [y0] and [y1]. */
    private fun apart(a: Pixels, b: Pixels, y0: Int, y1: Int): Int =
        (y0 until y1).maxOf { y -> (0 until a.width).maxOf { x -> Pixels.apart(a[x, y], b[x, y]) } }

    @Test
    fun `dots pulse after the pending queue value and nothing else changes`() {
        var wordWidth = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val measurer = rememberTextMeasurer()
            wordWidth = measurer.measure(AnnotatedString(rows[queue].value), faces().textType(QuickGrid.VALUE_SP, QuickGrid.VALUE_WEIGHT)).size.width
            QuickBar(QuickState(rows, queue), appearance, pending = pending)
        }
        val labels = (QuickGrid.LABEL_BASELINE - 16f).toInt() to (QuickGrid.LABEL_BASELINE + 5f).toInt()
        val values = (QuickGrid.VALUE_BASELINE - 20f).toInt() to (QuickGrid.VALUE_BASELINE + 6f).toInt()
        for (wall in Appearance.entries) {
            appearance = wall
            pending = false
            settle()
            val before = compose.pixels()
            pending = true
            pass(400)
            val first = compose.pixels()
            pass(300)
            val later = compose.pixels()
            // The labels never change, and the word keeps its pixels: the dots come only after it.
            assertThat(changed(before, first, labels.first, labels.second), name = "$wall labels").isEmpty()
            val wordEnd = QuickGrid.LEFT.toInt() + wordWidth
            assertThat(changed(before, first, values.first, values.second).min(), name = "$wall first change").isGreaterThanOrEqualTo(wordEnd)
            // The dots stand out, and they pulse.
            assertThat(apart(before, first, values.first, values.second), name = "$wall dots").isGreaterThan(60)
            assertThat(apart(first, later, values.first, values.second), name = "$wall pulse").isGreaterThan(20)
            // Once the new view shows, the dots fade out and the bar is as it was.
            pending = false
            pass(500)
            assertThat(apart(before, compose.pixels(), 0, before.height), name = "$wall after the wait").isEqualTo(0)
        }
    }

    /**
     * The dots over a still stage: the queue's entry chosen on both backgrounds, at rest beside
     * the chosen one, and the menu's Queue line.
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
                    // The stage stands still; only the dots move.
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
            Shot("light", Appearance.LIGHT, false, queue),
            Shot("rest", Appearance.DARK, false, queue + 1),
            Shot("menu", Appearance.DARK, true, queue),
        )) {
            pending = false
            appearance = wall
            menu = inMenu
            cursor = at
            settle()
            pending = true
            pass(700)
            compose.onRoot().captureRoboImage("build/screenshots/quick_pending/$shot.png")
        }
    }

    private data class Shot(val name: String, val wall: Appearance, val menu: Boolean, val cursor: Int)

    private companion object {
        /** A frame of the test's clock (ms). */
        const val FRAME = 16
    }
}
