package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.FlowEntry
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A stage still on screen after its song has ended keeps its own lines: while the next song's
 * scene is still being made, its time and its "Up next" stay its own (until 2026.10 they showed
 * the new song's at once, and so did the lines leaving in the change).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class OwnLinesTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)
    private val a = Fixtures.track(SyntheticCover.MOSS)
    private val b = Fixtures.track(SyntheticCover.CONFETTI)
    private val c = Fixtures.track(SyntheticCover.NOCTURNE)

    @Test
    fun singleCoverKeepsItsTimeAndNext() {
        val stage = Fixtures.stage(a, mdpi)
        keepsItsLines(UiState(screen = Screen.Stage(a, PlayerStatus.PLAYING, b), stage = stage), column = 520 until 872)
    }

    @Test
    fun coverFlowKeepsItsTimeAndNext() {
        val scene = Fixtures.scene(a, mdpi, view = StageView.FLOW)
        val flow = FlowData(listOf(FlowEntry(0L, a, scene)), 0)
        keepsItsLines(UiState(screen = Screen.Stage(a, PlayerStatus.PLAYING, b), view = StageView.FLOW, flow = flow), column = 598 until 912)
    }

    /** A plays at 1:05 with B up next; then B plays from its start with C up next, its scene not made yet. */
    private fun keepsItsLines(start: UiState, column: IntRange) {
        var position = 65_000L
        var ui by mutableStateOf(start)
        compose.mainClock.autoAdvance = false
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionNow = { position }, now = { 0L })) }
        compose.mainClock.advanceTimeBy(3_000)
        val before = capture()
        position = 2_000L
        ui = ui.copy(screen = Screen.Stage(b, PlayerStatus.PLAYING, c))
        compose.mainClock.advanceTimeBy(1_000)
        val after = capture()
        assertThat(difference(before, after, column)).isLessThan(0.05f)
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** The mean difference (0 to 255) between two frames over the text column. */
    private fun difference(p: Bitmap, q: Bitmap, column: IntRange): Float {
        var sum = 0L
        var n = 0
        for (y in 0 until p.height) for (x in column) {
            val u = p.getPixel(x, y)
            val v = q.getPixel(x, y)
            sum += abs((u shr 16 and 0xFF) - (v shr 16 and 0xFF)) + abs((u shr 8 and 0xFF) - (v shr 8 and 0xFF)) + abs((u and 0xFF) - (v and 0xFF))
            n += 3
        }
        return sum.toFloat() / n
    }
}
