package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import io.github.hazymorning.klangbild.app.MenuState
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsMenu
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.render.FlowLight
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.WallGlow
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * OLED: the band next to the screen edges stays black. This is checked on the rendered pixels
 * of every test scene, beside a single cover, in Cover Flow and behind the menu, not on the
 * light model. Only the cover colors fill the screen to its edges, wherever the stage drifts.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class EdgeBandTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun theScreenEdgesStayBlack() {
        val models = Fixtures.tracks.map { Fixtures.model(it, screen = mdpi) }
        var model by mutableStateOf(models.first())
        compose.setContent { Fixtures.Still { Stage(model) } }
        for (m in models) {
            model = m
            assertThat(brightestInBand(shown()), name = m.text.title).isEqualTo(0)
        }
    }

    @Test
    fun theEdgesAndTheTextStayOnBlackInCoverFlow() {
        // Every test cover once in the middle (its top edge colors the light), with two cards on
        // each side, which reach into the band, and in the strips with cards missing (the first
        // song, the last, no queue), where the strip, its light and the column move to stand in the
        // middle: the light never reaches the band, nor the column.
        val tracks = Fixtures.tracks
        val scenes = Fixtures.flowScenes(tracks, screen = mdpi)
        val geometry = FlowGeometry(scenes[0].layout)
        var middle by mutableStateOf(0)
        var sides by mutableStateOf(0 to 0)
        compose.setContent {
            Fixtures.Still {
                val (left, right) = sides
                val shown = (-left..right).map { scenes[(middle + it + scenes.size) % scenes.size] }
                FlowStage(Fixtures.model(tracks[middle], screen = mdpi, scene = scenes[middle]), Fixtures.restingCards(shown, left))
            }
        }
        for (i in tracks.indices) for ((left, right) in listOf(2 to 2, 0 to 0, 2 to 0, 0 to 2)) {
            middle = i
            sides = left to right
            val what = "${tracks[i].title}, $left before, $right to come"
            val px = shown()
            assertThat(brightestInBand(px), name = what).isEqualTo(0)
            // Up to the column's edge, where the first letters' soft edges may begin.
            val column = scenes[i].layout.columnLeft + geometry.balance(left, right).column
            val before = px.brightest((column - FlowLight.GONE_BEFORE_COLUMN).toInt(), 0, (column - 2f).toInt(), px.height)
            assertThat(before, name = "$what: before the column").isEqualTo(0)
        }
    }

    @Test
    fun theCoverColorsReachEveryEdgeWhereverTheStageDrifts() {
        // The cover colors background is the one that fills the screen: it meets the light behind the TV.
        // The screen drifts up to 6 dp against burn-in. Moved twice as far, the field still reaches every edge.
        val scenes = Fixtures.tracks.take(6).map { Fixtures.scene(it, mdpi, coverField = true) }
        var i by mutableStateOf(0)
        var at by mutableStateOf(0.0)
        var dx by mutableStateOf(0f)
        var dy by mutableStateOf(0f)
        compose.setContent {
            Fixtures.Still {
                Box(
                    Modifier.fillMaxSize().background(Color.Black).graphicsLayer {
                        translationX = dx
                        translationY = dy
                    },
                ) {
                    CompositionLocalProvider(LocalFieldTime provides FieldTime({ at }, false)) {
                        Stage(Fixtures.model(Fixtures.tracks[i], screen = mdpi, scene = scenes[i]))
                    }
                }
            }
        }
        for (k in scenes.indices) {
            i = k
            for ((t, d) in listOf(0.0 to (12f to 12f), 53.0 to (-12f to -12f), 211.0 to (12f to -12f))) {
                at = t
                dx = d.first
                dy = d.second
                assertThat(darkestOnTheEdges(shown()), name = "${Fixtures.tracks[k].title} at $t s, drift $d").isGreaterThanOrEqualTo(6)
            }
        }
    }

    @Test
    fun theScreenEdgesStayBlackBehindTheMenu() {
        val settings = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.wohnzimmer")
        val section = MenuSection.INFO
        val rows = SettingsMenu.rows(settings, MenuContext(), Fixtures.texts, section)
        var state by mutableStateOf(Fixtures.menuGroups())
        compose.setContent { Fixtures.Still { MenuScreen(state) } }
        for (c in listOf(0, SettingsMenu.sections.size - 1)) {
            state = Fixtures.menuGroups(cursor = c)
            assertThat(brightestInBand(shown()), name = "group $c").isEqualTo(0)
        }
        for (c in listOf(0, rows.size / 2, rows.size - 1)) {
            state = MenuState(rows, c, section = section)
            assertThat(brightestInBand(shown()), name = rows[c].label).isEqualTo(0)
        }
    }

    /** What shows once the screen has settled. */
    private fun shown(): Pixels {
        compose.waitForIdle()
        return compose.pixels()
    }

    /** The darkest pixel on the screen's outermost rows and columns (its brightest channel). */
    private fun darkestOnTheEdges(px: Pixels): Int {
        var darkest = 255
        for (y in 0 until px.height) for (x in 0 until px.width) {
            if (x != 0 && x != px.width - 1 && y != 0 && y != px.height - 1) continue
            darkest = minOf(darkest, px.level(x, y))
        }
        return darkest
    }

    /** The brightest channel value in the band along the screen edges. */
    private fun brightestInBand(px: Pixels): Int {
        val band = WallGlow.EDGE_BLACK_DP.toInt()
        var brightest = 0
        for (y in 0 until px.height) for (x in 0 until px.width) {
            if (x >= band && x < px.width - band && y >= band && y < px.height - band) continue
            brightest = maxOf(brightest, px.level(x, y))
        }
        return brightest
    }
}
