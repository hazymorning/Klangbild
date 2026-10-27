package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isGreaterThan
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * On the dark wall a light card beside a dark middle one steps back. When the strip turns to
 * that card, it brightens with the turn: in the turn's first frames, before the strip has moved,
 * it is as bright as it was.
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
        val s = covers.mapIndexed { i, c -> i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, appearance = Appearance.DARK, flow = true) }.toMap()
        var flow by mutableStateOf(Fixtures.flow(s, listOf(0, 1, 2, 3), 1))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheaterOf(flow) }
        compose.mainClock.advanceTimeBy(3_000)
        val before = sideCard(compose.pixels())
        // The strip turns to the light card beside the dark one; three frames later the turn is
        // drawn, though the cards have hardly moved.
        flow = Fixtures.flow(s, listOf(0, 1, 2, 3), 2)
        compose.mainClock.advanceTimeBy(48)
        val first = sideCard(compose.pixels())
        assertThat(first).isCloseTo(before, before * 0.1f)
        // Once the turn has landed, the light card stands in the middle at its full brightness.
        compose.mainClock.advanceTimeBy(3_000)
        assertThat(middleCard(compose.pixels())).isGreaterThan(before * 1.5f)
    }

    /** The mean of each pixel's brightest channel inside the first card right of the middle one. */
    private fun sideCard(px: Pixels) = px.mean(465, 180, 515, 360)

    /** The same inside the middle card. */
    private fun middleCard(px: Pixels) = px.mean(220, 180, 380, 360)
}
