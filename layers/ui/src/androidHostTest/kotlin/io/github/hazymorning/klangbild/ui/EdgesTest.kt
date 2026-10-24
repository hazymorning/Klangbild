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
import assertk.assertions.isLessThanOrEqualTo
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.AppearanceMode
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.Settings
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
 * The screen drifts a few pixels against burn-in. Whatever shows (paper, the menu, the quick
 * settings, a wall turning from light to dark), its ground reaches beyond the edges, so no
 * strip of anything else ever shows at a side (measured on rendered pixels, the drift at its
 * widest).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class EdgesTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)
    private val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
    private val light = Fixtures.stage(track, mdpi, appearance = Appearance.LIGHT)
    private val dark = Fixtures.stage(track, mdpi, appearance = Appearance.DARK)

    private fun menu(s: Settings) = Fixtures.menu(MenuEntry.APPEARANCE, s)

    private fun quick(): QuickState {
        val rows = QuickMenu.rows(Settings(), QuickContext(pending = null, music = true, favorite = null, shuffle = false), Fixtures.texts)
        return QuickState(rows, 0)
    }

    private fun frame(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** How far the outermost columns (and the last row) differ from the pixels just inside them (levels). */
    private fun stripe(bmp: Bitmap, bottom: Boolean = false): Int {
        var worst = 0
        fun diff(a: Int, b: Int) = maxOf(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))
        for (y in 0 until bmp.height step 3) {
            worst = maxOf(worst, diff(bmp.getPixel(0, y), bmp.getPixel(14, y)), diff(bmp.getPixel(bmp.width - 1, y), bmp.getPixel(bmp.width - 15, y)))
        }
        if (bottom) for (x in 0 until bmp.width step 3) worst = maxOf(worst, diff(bmp.getPixel(x, bmp.height - 1), bmp.getPixel(x, bmp.height - 15)))
        return worst
    }

    @Test
    fun `no strip at the sides with paper, the menu, a wall turning dark or the quick settings`() {
        var ui by mutableStateOf(UiState(screen = Screen.Stage(track, PlayerStatus.PAUSED, null), stage = light, appearance = Appearance.LIGHT))
        compose.mainClock.autoAdvance = false
        // Nine minutes into the hour the drift stands at its right end; the stage paused, so it rests.
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { 60_000L }, now = { 9 * 60_000L }, glyphs = AndroidGlyphs)) }
        compose.mainClock.advanceTimeBy(2_500)
        assertThat(stripe(frame()), name = "paper").isLessThanOrEqualTo(4)
        ui = ui.copy(menu = menu(Settings()))
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(stripe(frame()), name = "menu on paper").isLessThanOrEqualTo(4)
        // Dark chosen in the menu: the menu turns dark at once, the wall behind it takes a while.
        ui = ui.copy(appearance = Appearance.DARK, stage = dark, menu = menu(Settings(appearance = AppearanceMode.DARK)))
        for (i in 1..8) {
            compose.mainClock.advanceTimeBy(300)
            assertThat(stripe(frame()), name = "menu turning dark, frame $i").isLessThanOrEqualTo(4)
        }
        // The menu closes while the wall still turns.
        ui = ui.copy(menu = null)
        for (i in 1..8) {
            compose.mainClock.advanceTimeBy(300)
            assertThat(stripe(frame()), name = "wall turning dark, frame $i").isLessThanOrEqualTo(4)
        }
        // The quick settings, on the dark wall and on paper: seamless to the sides and the bottom.
        ui = ui.copy(quick = quick())
        compose.mainClock.advanceTimeBy(1_000)
        // (The chosen entry's soft light reaches a few levels into the pixels compared.)
        assertThat(stripe(frame(), bottom = true), name = "quick settings, dark").isLessThanOrEqualTo(12)
        ui = ui.copy(appearance = Appearance.LIGHT, stage = light)
        compose.mainClock.advanceTimeBy(3_000)
        assertThat(stripe(frame(), bottom = true), name = "quick settings, paper").isLessThanOrEqualTo(12)
    }
}
