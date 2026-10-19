package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.Fixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * After the clock, the setup page or the landscape, a song other than the one the stage held
 * waits for its own scene: the stage left from before never comes up for it (until 2026.10 the
 * song before came up first, and the lights took its colors). While the stage shows, it stays
 * until the next song's scene is ready.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class HeldStageTest {
    private val mdpi = ScreenSpec(960, 540, 1f)
    private val a = Fixtures.stage(Fixtures.track(SyntheticCover.MOSS), mdpi)
    private val b = Fixtures.track(SyntheticCover.CONFETTI)
    private val shown = UiState(screen = playing(a.track), stage = a)

    private fun playing(t: TrackInfo) = Screen.Stage(t, PlayerStatus.PLAYING, null)

    @Test
    fun aNewSongAfterTheClockNeverShowsTheStageBefore() {
        val clock = shown.copy(screen = Screen.Rest(RestReason.PAUSED, "Wohnzimmer"))
        assertThat(HeldStage.stale(clock, playing(b), null)).isTrue()
        // The same song again comes back as it was, at once.
        assertThat(HeldStage.stale(clock, playing(a.track), null)).isFalse()
    }

    @Test
    fun norAfterTheLandscapeOrTheSetupPage() {
        val landscape = shown.copy(landscape = Fixtures.landscape(a.track, PlayerStatus.PAUSED, MiniPlayer.LEFT))
        assertThat(HeldStage.stale(landscape, playing(b), null)).isTrue()
        // While the landscape still shows, the stage behind it is made ready meanwhile.
        assertThat(HeldStage.stale(landscape, playing(b), landscape.landscape)).isFalse()
        val setup = shown.copy(screen = Screen.Setup(SetupReason.REQUESTED))
        assertThat(HeldStage.stale(setup, playing(b), null)).isTrue()
    }

    @Test
    fun onTheStageTheStageBeforeStaysUntilTheNextSceneIsReady() {
        assertThat(HeldStage.stale(shown, playing(b), null)).isFalse()
    }

    @Test
    fun coverFlowIsHeldTheSameWay() {
        val flow = FlowData(listOf(FlowEntry(0L, a.track, a.scene)), 0)
        val clock = UiState(screen = Screen.Rest(RestReason.IDLE), view = StageView.FLOW, flow = flow)
        assertThat(HeldStage.stale(clock, playing(b), null)).isTrue()
        assertThat(HeldStage.stale(clock, playing(a.track), null)).isFalse()
    }

    @Test
    fun withNothingHeldThereIsNothingToDrop() {
        assertThat(HeldStage.stale(UiState(screen = Screen.Rest(RestReason.IDLE)), playing(b), null)).isFalse()
        assertThat(HeldStage.stale(shown.copy(screen = Screen.Rest(RestReason.IDLE)), null, null)).isFalse()
    }
}
