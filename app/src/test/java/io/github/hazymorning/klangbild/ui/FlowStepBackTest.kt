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
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On the dark wall a light card beside a dark middle one steps back. When the strip turns to
 * that card, it brightens with the turn: in the turn's first frames, before the strip has moved,
 * it is as bright as it was (until 2026.10 it doubled its brightness at once).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class FlowStepBackTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun aLightCardBesideTheMiddleBrightensWithTheTurn() {
        val covers = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.NOCTURNE, SyntheticCover.WHITE_SPACE, SyntheticCover.MOSS)
        val s = covers.mapIndexed { i, c -> i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, appearance = Appearance.DARK, view = StageView.FLOW) }.toMap()
        var flow by mutableStateOf(Fixtures.flow(s, listOf(0, 1, 2, 3), 1))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheaterOf(flow) }
        compose.mainClock.advanceTimeBy(3_000)
        val before = sideCard(capture())
        // The strip turns to the light card beside the dark one; three frames later the turn is
        // drawn, though the cards have hardly moved.
        flow = Fixtures.flow(s, listOf(0, 1, 2, 3), 2)
        compose.mainClock.advanceTimeBy(48)
        val first = sideCard(capture())
        assertThat(first).isWithin(before * 0.1f).of(before)
        // Once the turn has landed, the light card stands in the middle at its full brightness.
        compose.mainClock.advanceTimeBy(3_000)
        assertThat(middleCard(capture())).isGreaterThan(before * 1.5f)
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** The mean of each pixel's brightest channel inside the first card right of the middle one. */
    private fun sideCard(b: Bitmap) = mean(b, 465, 180, 515, 360)

    /** The same inside the middle card. */
    private fun middleCard(b: Bitmap) = mean(b, 220, 180, 380, 360)

    private fun mean(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val w = x1 - x0
        val px = IntArray(w * (y1 - y0)).also { b.getPixels(it, 0, w, x0, y0, w, y1 - y0) }
        return px.map { maxOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }.average().toFloat()
    }
}
