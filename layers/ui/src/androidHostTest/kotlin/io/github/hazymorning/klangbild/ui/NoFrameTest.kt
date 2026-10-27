package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test

/**
 * Nothing frames a cover on the light background: no shadow, no line, no lighter or darker box.
 * Around the single cover and above and below Cover Flow's cards the background is plain,
 * measured on rendered pixels. Nor does anything darken Cover Flow's middle card at an edge,
 * wherever the strip stands.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class NoFrameTest {
    @get:Rule
    val compose = composeRule()

    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)

    private fun render(content: @Composable () -> Unit): Pixels {
        if (shown == null) compose.setContent { shown?.let { Fixtures.Still(it) } }
        shown = content
        compose.waitForIdle()
        return compose.pixels()
    }

    /** The largest difference (levels, any channel) from the background in the boxes (px: x0, y0, x1, y1). */
    private fun deviation(px: Pixels, boxes: List<IntArray>): Int {
        val background = px[boxes[0][0], boxes[0][1]]
        var worst = 0
        for (b in boxes) for (y in b[1] until b[3]) for (x in b[0] until b[2]) worst = maxOf(worst, Pixels.apart(px[x, y], background))
        return worst
    }

    @Test
    fun `around the single cover the background is plain`() {
        for (cover in listOf(SyntheticCover.NIGHT_DRIVE, SyntheticCover.WHITE_SPACE, SyntheticCover.BAUHAUS)) {
            val model = Fixtures.model(Fixtures.track(cover), next = Fixtures.spiegel, group = "Wohnzimmer", appearance = Appearance.LIGHT)
            val px = render { Stage(model) }
            // The artwork: 368 dp at 88 dp from the left, centered, at 2 px a dp. A ring from 3 to 40 dp around it.
            val l = 176; val t = 172; val r = 912; val b = 908
            val boxes = listOf(
                intArrayOf(l - 80, t - 80, r + 80, t - 6),
                intArrayOf(l - 80, b + 6, r + 80, b + 80),
                intArrayOf(l - 80, t, l - 6, b),
                intArrayOf(r + 6, t, r + 80, b),
            )
            assertThat(deviation(px, boxes), name = "$cover: the background round the cover").isLessThanOrEqualTo(3)
        }
    }

    @Test
    fun `above and below Cover Flow's cards the background is plain`() {
        val strip: List<TrackInfo> = listOf(
            Fixtures.track(SyntheticCover.TERRACOTTA),
            Fixtures.radio,
            Fixtures.track(SyntheticCover.NIGHT_DRIVE),
            Fixtures.track(SyntheticCover.WHITE_SPACE),
            Fixtures.track(SyntheticCover.BAUHAUS),
        )
        val scenes = Fixtures.flowScenes(strip, Appearance.LIGHT)
        val model = Fixtures.model(strip[2], next = strip[3], group = "Wohnzimmer", scene = scenes[2])
        val px = render { FlowStage(model, Fixtures.restingCards(scenes, 2)) }
        // The middle card: 300 dp, its middle 303 dp from the left and in the middle of the height; the column from 598 dp.
        val top = 240; val bottom = 840
        val boxes = listOf(
            intArrayOf(80, top - 90, 1110, top - 6),
            intArrayOf(80, bottom + 6, 1110, bottom + 90),
        )
        assertThat(deviation(px, boxes), name = "the background above and below the cards").isLessThanOrEqualTo(3)
    }

    @Test
    fun `the middle card keeps every pixel wherever the strip stands`() {
        // A first song brings the middle card near the left edge, where the side cards dissolve into the black band.
        val strip = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.MOSS, SyntheticCover.WHITE_SPACE, SyntheticCover.NIGHT_DRIVE, SyntheticCover.BAUHAUS)
            .map { Fixtures.track(it) }
        val scenes = Fixtures.flowScenes(strip)
        val geometry = FlowGeometry(scenes[2].layout)
        val model = Fixtures.model(strip[2], scene = scenes[2])
        fun shot(left: Int, right: Int) = render { FlowStage(model, Fixtures.restingCards(scenes.subList(2 - left, 3 + right), left)) }
        val full = shot(2, 2)
        for ((left, right) in listOf(0 to 2, 0 to 1, 0 to 0, 2 to 0)) {
            val px = shot(left, right)
            // The card stands on whole pixels: the strip's shift, rounded one way or the other.
            val dx = geometry.balance(left, right).cards * 2f
            val worst = listOf(floor(dx), ceil(dx)).minOf { middleCardDifference(full, px, it.toInt()) }
            assertThat(worst, name = "$left before, $right to come").isEqualTo(0)
        }
    }

    /** The largest difference (levels, any channel) between the middle card in [a] and in [b], [dx] px further right (round corners left out). */
    private fun middleCardDifference(a: Pixels, b: Pixels, dx: Int): Int {
        // The middle card: 300 dp, its middle 303 dp from the left and in the middle of the height, at 2 px a dp.
        var worst = 0
        for (y in 256 until 824) for (x in 306 until 906) worst = maxOf(worst, Pixels.apart(a[x, y], b[x + dx, y]))
        return worst
    }
}
