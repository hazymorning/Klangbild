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
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.FlowEntry
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.theme.AndroidGlyphs
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test

/**
 * A stage still on screen after its song has ended keeps its own lines: while the next song's
 * scene is still being made, its time and its "Up next" stay its own, also when the player
 * reports the next song a moment before the screen changes to it.
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
        keepsItsLines(UiState(screen = Screen.Stage(a, PlayerStatus.PLAYING, b), stage = stage))
    }

    @Test
    fun coverFlowKeepsItsTimeAndNext() {
        val scene = Fixtures.scene(a, mdpi, flow = true)
        val flow = FlowData(listOf(FlowEntry(0L, a, scene)), 0)
        keepsItsLines(UiState(screen = Screen.Stage(a, PlayerStatus.PLAYING, b), queue = QueueDisplay.FLOW, flow = flow))
    }

    /**
     * A plays at 1:05 with B up next; then the player reports B from its start, and a few ticks
     * later the screen shows B with C up next, its scene not made yet.
     */
    private fun keepsItsLines(start: UiState) {
        var reported = a.key
        var position = 65_000L
        var ui by mutableStateOf(start)
        compose.mainClock.autoAdvance = false
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { key -> position.takeIf { key == reported } }, now = { 0L }, glyphs = AndroidGlyphs)) }
        compose.mainClock.advanceTimeBy(3_000)
        val before = capture()
        reported = b.key
        position = 2_000L
        compose.mainClock.advanceTimeBy(100)
        // Drawn meanwhile, as every frame is on the TV.
        capture()
        ui = ui.copy(screen = Screen.Stage(b, PlayerStatus.PLAYING, c))
        compose.mainClock.advanceTimeBy(1_000)
        val after = capture()
        assertThat(changed(before, after)).isEqualTo(0)
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** How many pixels two frames show visibly apart (the column stands elsewhere with one card). */
    private fun changed(p: Bitmap, q: Bitmap): Int {
        var n = 0
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val u = p.getPixel(x, y)
            val v = q.getPixel(x, y)
            val d = abs((u shr 16 and 0xFF) - (v shr 16 and 0xFF)) + abs((u shr 8 and 0xFF) - (v shr 8 and 0xFF)) + abs((u and 0xFF) - (v and 0xFF))
            if (d > VISIBLE) n++
        }
        return n
    }

    private companion object {
        /** A difference (summed over the channels) anyone would see. */
        const val VISIBLE = 24
    }
}
