package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.render.FlowLight
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.WallGlow
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * OLED: the band next to the screen edges stays black. This is checked on the rendered pixels
 * of every test scene, in both views and behind the menu, not on the light model. Only the cover
 * colors fill the screen to its edges, wherever the stage drifts.
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
            assertWithMessage(m.text.title).that(brightestInBand()).isEqualTo(0)
        }
    }

    @Test
    fun theScreenEdgesStayBlackInCoverFlow() {
        // Every test cover once, five to a strip: the cards beside the middle reach into the band.
        val strips = Fixtures.tracks.chunked(5).filter { it.size == 5 }
        val scenes = strips.map { Fixtures.flowScenes(it, screen = mdpi) }
        var strip by mutableStateOf(0)
        compose.setContent {
            Fixtures.Still {
                val s = scenes[strip]
                FlowStage(Fixtures.model(strips[strip][2], screen = mdpi, scene = s[2]), Fixtures.restingCards(s, 2))
            }
        }
        for (i in strips.indices) {
            strip = i
            assertWithMessage(strips[i][2].title).that(brightestInBand()).isEqualTo(0)
        }
    }

    @Test
    fun theEdgesAndTheTextStayOnBlackWithCardsMissing() {
        // The first song, the last, no queue: the strip and its light move to stand in the middle,
        // the column moves with them. Every test cover once in the middle (its top edge colors the
        // light), in the strips that move them most: the light never reaches the band, nor the column.
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
        for (i in tracks.indices) for ((left, right) in listOf(0 to 0, 2 to 0, 0 to 2)) {
            middle = i
            sides = left to right
            val what = "${tracks[i].title}, $left before, $right to come"
            assertWithMessage(what).that(brightestInBand()).isEqualTo(0)
            // Up to the column's edge, where the first letters' soft edges may begin.
            val column = scenes[i].layout.columnLeft + geometry.balance(left, right).column
            assertWithMessage("$what: before the column").that(brightestBetween(column - FlowLight.GONE_BEFORE_COLUMN, column - 2f)).isEqualTo(0)
        }
    }

    @Test
    fun theCoverColorsReachEveryEdgeWhereverTheStageDrifts() {
        // The cover colors background is the one wall that fills the screen: it meets the light behind the TV.
        // The stage drifts ±12 dp against burn-in; the field reaches beyond, so no edge ever shows black.
        val scenes = Fixtures.tracks.take(6).map { Fixtures.scene(it, mdpi, coverField = true) }
        var i by mutableStateOf(0)
        var at by mutableStateOf(0.0)
        var dx by mutableStateOf(0f)
        var dy by mutableStateOf(0f)
        compose.setContent {
            Fixtures.Still {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).graphicsLayer {
                        translationX = dx
                        translationY = dy
                    },
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        io.github.hazymorning.klangbild.ui.stage.LocalFieldTime provides
                            io.github.hazymorning.klangbild.ui.stage.FieldTime({ at }, false),
                    ) {
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
                assertWithMessage("${Fixtures.tracks[k].title} at $t s, drift $d").that(darkestOnTheEdges()).isAtLeast(6)
            }
        }
    }

    @Test
    fun theScreenEdgesStayBlackBehindTheMenu() {
        val settings = io.github.hazymorning.klangbild.config.Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.wohnzimmer")
        val section = io.github.hazymorning.klangbild.config.MenuSection.INFO
        val rows = io.github.hazymorning.klangbild.config.SettingsMenu.rows(settings, io.github.hazymorning.klangbild.config.MenuContext(), Fixtures.texts, section)
        var state by mutableStateOf(Fixtures.menuGroups())
        compose.setContent { Fixtures.Still { MenuScreen(state) } }
        val groups = io.github.hazymorning.klangbild.config.SettingsMenu.sections.size
        for (c in listOf(0, groups - 1)) {
            state = Fixtures.menuGroups(cursor = c)
            assertWithMessage("group $c").that(brightestInBand()).isEqualTo(0)
        }
        for (c in listOf(0, rows.size / 2, rows.size - 1)) {
            state = io.github.hazymorning.klangbild.app.MenuState(rows, c, section = section)
            assertWithMessage(rows[c].label).that(brightestInBand()).isEqualTo(0)
        }
    }

    /** The darkest pixel on the screen's outermost rows and columns (its brightest channel). */
    private fun darkestOnTheEdges(): Int {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var darkest = 255
        for (y in 0 until h) for (x in 0 until w) {
            if (x != 0 && x != w - 1 && y != 0 && y != h - 1) continue
            val p = px[y * w + x]
            darkest = minOf(darkest, maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF))
        }
        return darkest
    }

    /** The brightest channel value from [fromDp] to [toDp] across the screen, all the way down. */
    private fun brightestBetween(fromDp: Float, toDp: Float): Int {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var brightest = 0
        for (y in 0 until h) for (x in fromDp.toInt() until toDp.toInt()) {
            val p = px[y * w + x]
            brightest = maxOf(brightest, (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }
        return brightest
    }

    /** The brightest channel value in the band along the screen edges. */
    private fun brightestInBand(): Int {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val band = WallGlow.EDGE_BLACK_DP.toInt()
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var brightest = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= band && x < w - band && y >= band && y < h - band) continue
            val p = px[y * w + x]
            brightest = maxOf(brightest, (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }
        return brightest
    }
}
