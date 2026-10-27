package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.contrastRatio
import io.github.hazymorning.klangbild.color.relativeLuminance
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.stage.FieldLayer
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * On the cover colors, the quietest text keeps 4.5:1 against the field pixel it stands out from
 * least anywhere in the text column, at four moments of the field's motion, and in Cover Flow
 * across every place the column can take.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class CoverFieldContrastTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun theQuietestTextKeepsItsContrastOnTheDarkField() = check(Appearance.DARK)

    @Test
    fun theQuietestTextKeepsItsContrastOnTheLightField() = check(Appearance.LIGHT)

    @Test
    fun inCoverFlowTheQuietestTextKeepsItsContrastWhereverTheColumnStands() = check(Appearance.DARK, flow = true)

    @Test
    fun inCoverFlowOnTheLightFieldTheQuietestTextKeepsItsContrastWhereverTheColumnStands() = check(Appearance.LIGHT, flow = true)

    private fun check(appearance: Appearance, flow: Boolean = false) {
        var shown by mutableStateOf<ArtworkScene?>(null)
        var at by mutableStateOf(0.0)
        compose.setContent {
            Fixtures.Still {
                CompositionLocalProvider(LocalFieldTime provides FieldTime({ at }, false)) { shown?.let { FieldLayer(it) } }
            }
        }
        for (track in Fixtures.tracks) {
            val scene = Fixtures.scene(track, mdpi, appearance, flow = flow, coverField = true)
            shown = scene
            for (t in listOf(0.0, 47.0, 133.0, 260.0)) {
                at = t
                val worst = worstBehindText(scene, darkText = appearance == Appearance.LIGHT)
                assertThat(contrastRatio(scene.palette.inkQuiet, worst), name = "${track.title}, $appearance, t = $t")
                    .isGreaterThanOrEqualTo(4.5f)
            }
        }
    }

    /** The field's pixel in the text column that the quietest text stands out from least. */
    private fun worstBehindText(scene: ArtworkScene, darkText: Boolean): Int {
        compose.waitForIdle()
        val px = compose.pixels()
        val l = scene.layout
        // In Cover Flow the column moves left when cards are missing.
        val reach = if (l.flow) FlowGeometry(l).leftmostColumn else 0f
        val x0 = (l.columnLeft + reach).toInt()
        val x1 = (l.columnLeft + l.columnWidth).toInt()
        val y0 = l.statusTop.toInt() - 4
        val y1 = l.footerBottom.toInt() + 4
        var worst = if (darkText) -1 else 0xFF000000.toInt()
        var worstY = if (darkText) 2f else -1f
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = px[x, y]
            val lum = relativeLuminance(p)
            if (if (darkText) lum < worstY else lum > worstY) {
                worstY = lum
                worst = p
            }
        }
        return worst
    }
}
