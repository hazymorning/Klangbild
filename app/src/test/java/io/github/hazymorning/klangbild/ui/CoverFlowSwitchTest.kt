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
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The queue goes to or from Cover Flow: what showed stays on screen until the first scene of the
 * other is made, then the two cross through the dark at once. The screen never waits in the dark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class CoverFlowSwitchTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)
    private val tracks = listOf(SyntheticCover.MOSS, SyntheticCover.MIST, SyntheticCover.BAUHAUS, SyntheticCover.SALT_WATER, SyntheticCover.CONFETTI).map(Fixtures::track)
    private val track = tracks[2]

    private fun flow(): FlowData {
        val scenes = Fixtures.flowScenes(tracks, screen = mdpi)
        return FlowData(tracks.indices.map { FlowEntry(it.toLong(), tracks[it], scenes[it]) }, 2)
    }

    @Test
    fun whatShowedStaysUntilTheOtherIsReady() {
        var ui by mutableStateOf(UiState(screen = Screen.Stage(track, PlayerStatus.PLAYING, null), stage = Fixtures.stage(track, mdpi)))
        compose.mainClock.autoAdvance = false
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { 60_000L }, now = { 0L })) }
        compose.mainClock.advanceTimeBy(2_500)
        val single = capture()
        // Cover Flow is chosen; its cards are still being made: the single cover stays as it is.
        ui = ui.copy(queue = QueueDisplay.FLOW)
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(capture().sameAs(single)).isTrue()
        // The cards are ready: the single cover fades out, and the cards come up right after.
        ui = ui.copy(flow = flow(), stage = null)
        compose.mainClock.advanceTimeBy(950)
        assertThat(brightest(capture())).isLessThan(40)
        compose.mainClock.advanceTimeBy(2_500)
        val cards = capture()
        assertThat(brightest(cards)).isGreaterThan(150)
        assertThat(cards.sameAs(single)).isFalse()
        // And back: the cards stay until the single cover is made again.
        ui = ui.copy(queue = QueueDisplay.OFF)
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(capture().sameAs(cards)).isTrue()
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun brightest(bmp: Bitmap): Int {
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return px.maxOf { maxOf((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
    }
}
