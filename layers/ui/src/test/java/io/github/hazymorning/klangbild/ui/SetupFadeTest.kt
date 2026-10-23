package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The setup page fades out as it showed: its server has stopped by then, and until 2026.10 its
 * code went blank and its address said there was no network while it faded.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class SetupFadeTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun theCodeStaysWhileThePageFades() {
        val info = SetupInfo(url = "http://192.168.1.23:8765", code = "123456", qr = null)
        var ui by mutableStateOf(UiState(screen = Screen.Setup(SetupReason.REQUESTED), setup = info))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Still { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { null }, now = { 0L })) } }
        compose.mainClock.advanceTimeBy(2_500)
        assertThat(shows("123 456")).isTrue()
        // Back: the music comes, and a moment later the server that made the code has stopped.
        val track = Fixtures.track(SyntheticCover.MOSS)
        ui = ui.copy(screen = Screen.Stage(track, PlayerStatus.PLAYING, null), stage = Fixtures.stage(track, mdpi))
        compose.mainClock.advanceTimeByFrame()
        // It starts to fade with its code (on the TV every frame is drawn).
        assertThat(shows("123 456")).isTrue()
        ui = ui.copy(setup = null)
        compose.mainClock.advanceTimeBy(300)
        assertThat(shows("123 456")).isTrue()
        assertThat(shows("192.168.1.23")).isTrue()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
}
