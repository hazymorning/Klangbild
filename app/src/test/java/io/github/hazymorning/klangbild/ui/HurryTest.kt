package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Only another song hurries a running change: a card that joins the strip or a better cover
 * waits until the change has landed. Two theaters side by side play the same change; one of
 * them gets something more 300 ms into it, and 800 ms in they are compared.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h540dp-land-mdpi")
class HurryTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    private val covers = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.MOSS, SyntheticCover.NIGHT_DRIVE, SyntheticCover.WHITE_SPACE, SyntheticCover.BAUHAUS)

    private val strip by lazy {
        covers.mapIndexed { i, c -> i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, appearance = Appearance.DARK, view = StageView.FLOW) }.toMap()
    }

    /** Both theaters go from [first] to [change]; the second gets [more] 300 ms later. Returns both, 800 ms into the change. */
    private fun <T> sideBySide(first: T, change: T, more: T, theater: @Composable (T) -> Unit): Pair<Bitmap, Bitmap> {
        var a by mutableStateOf(first)
        var b by mutableStateOf(first)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Row {
                Box(Modifier.size(960.dp, 540.dp).testTag("a")) { theater(a) }
                Box(Modifier.size(960.dp, 540.dp).testTag("b")) { theater(b) }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        a = change
        b = change
        compose.mainClock.advanceTimeBy(300)
        b = more
        compose.mainClock.advanceTimeBy(500)
        return capture("a") to capture("b")
    }

    private fun capture(tag: String) = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    /**
     * How many pixels of [a] and [b] differ by more than a level or two. The same picture drawn
     * at another place on the screen rounds a few edge pixels differently.
     */
    private fun differing(a: Bitmap, b: Bitmap): Int {
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return pa.indices.count { i -> (0..16 step 8).any { s -> abs((pa[i] shr s and 0xFF) - (pb[i] shr s and 0xFF)) > 2 } }
    }

    private fun assertAlike(pair: Pair<Bitmap, Bitmap>) = assertThat(differing(pair.first, pair.second)).isEqualTo(0)

    /** Another moment of the change: far more than a few pixels apart. */
    private fun assertApart(pair: Pair<Bitmap, Bitmap>) = assertThat(differing(pair.first, pair.second)).isGreaterThan(5_000)

    @Test
    fun aCardThatJoinsTheStripWaitsForTheTurn() {
        val pair = sideBySide(
            Fixtures.flow(strip, listOf(0, 1, 2, 3), 1),
            Fixtures.flow(strip, listOf(0, 1, 2, 3), 2),
            Fixtures.flow(strip, listOf(0, 1, 2, 3, 4), 2),
        ) { Fixtures.FlowTheaterOf(it) }
        assertAlike(pair)
    }

    @Test
    fun anotherSongHurriesTheTurn() {
        val pair = sideBySide(
            Fixtures.flow(strip, listOf(0, 1, 2, 3), 1),
            Fixtures.flow(strip, listOf(0, 1, 2, 3), 2),
            Fixtures.flow(strip, listOf(0, 1, 2, 3, 4), 3),
        ) { Fixtures.FlowTheaterOf(it) }
        assertApart(pair)
    }

    @Test
    fun aBetterCoverWaitsForTheTrackChange() {
        val song = Fixtures.track(SyntheticCover.MIST)
        val pair = sideBySide(
            Fixtures.stage(Fixtures.track(SyntheticCover.NIGHT_DRIVE), mdpi),
            Fixtures.stage(song, mdpi),
            // A better cover for the same song: a new scene, here its cover's colors as the wall.
            Fixtures.stage(song, mdpi, coverField = true),
        ) { Fixtures.Theater(it, PlayerStatus.PLAYING) }
        assertAlike(pair)
    }

    @Test
    fun anotherSongHurriesTheTrackChange() {
        val pair = sideBySide(
            Fixtures.stage(Fixtures.track(SyntheticCover.NIGHT_DRIVE), mdpi),
            Fixtures.stage(Fixtures.track(SyntheticCover.MIST), mdpi),
            Fixtures.stage(Fixtures.track(SyntheticCover.SALT_WATER), mdpi),
        ) { Fixtures.Theater(it, PlayerStatus.PLAYING) }
        assertApart(pair)
    }
}
