package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
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
 * After the clock, the setup page or the landscape, a song other than the one the stage kept
 * waits for its own scene, and so does the same song when the wall or the view has changed
 * meanwhile: the stage left from before never comes up for it (until 2026.10 the song before
 * came up first and the lights took its colors). Meanwhile the clock or the landscape stays,
 * never the dark; a screen that says something is wrong and the setup page go through the dark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class HeldStageTest {
    private val mdpi = ScreenSpec(960, 540, 1f)
    private val a = Fixtures.stage(Fixtures.track(SyntheticCover.MOSS), mdpi)
    private val b = Fixtures.track(SyntheticCover.CONFETTI)
    private val dark = Look(Appearance.DARK, 1f)
    private val onStage = UiState(screen = playing(a.track), stage = a)
    private val clock = onStage.copy(screen = Screen.Rest(RestReason.PAUSED, "Wohnzimmer"))
    private val landscape = onStage.copy(landscape = Fixtures.landscape(a.track, PlayerStatus.PAUSED, MiniPlayer.LEFT))

    private fun playing(t: TrackInfo) = Screen.Stage(t, PlayerStatus.PLAYING, null)

    private fun stale(before: UiState, stage: Screen.Stage?, view: StageView = StageView.SINGLE, look: Look = dark, landscape: Boolean = false) =
        HeldStage().stale(before, view, look::fits, stage, landscape)

    @Test
    fun aNewSongAfterTheClockNeverShowsTheStageBefore() {
        assertThat(stale(clock, playing(b))).isTrue()
        // The same song again comes back as it was, at once.
        assertThat(stale(clock, playing(a.track))).isFalse()
    }

    @Test
    fun theSameSongWaitsForItsSceneOnAWallChangedMeanwhile() {
        assertThat(stale(clock, playing(a.track), look = Look(Appearance.LIGHT, 1f))).isTrue()
    }

    @Test
    fun norAfterTheLandscapeOrTheSetupPage() {
        assertThat(stale(landscape, playing(b))).isTrue()
        // While the landscape still shows, the stage behind it is made ready meanwhile.
        assertThat(stale(landscape, playing(b), landscape = true)).isFalse()
        val setup = onStage.copy(screen = Screen.Setup(SetupReason.REQUESTED))
        assertThat(stale(setup, playing(b))).isTrue()
    }

    @Test
    fun onTheStageTheStageBeforeStaysUntilTheNextSceneIsReady() {
        assertThat(stale(onStage, playing(b))).isFalse()
    }

    @Test
    fun coverFlowIsKeptTheSameWay() {
        val flow = FlowData(listOf(FlowEntry(0L, a.track, a.scene)), 0)
        val idle = UiState(screen = Screen.Rest(RestReason.IDLE), view = StageView.FLOW, flow = flow)
        assertThat(stale(idle, playing(b), view = StageView.FLOW)).isTrue()
        assertThat(stale(idle, playing(a.track), view = StageView.FLOW)).isFalse()
    }

    @Test
    fun aViewChangedMeanwhileNeverBringsBackTheOtherView() {
        // Cover Flow was chosen over the clock: the single cover kept from before stays away.
        assertThat(stale(clock, playing(a.track), view = StageView.FLOW)).isTrue()
    }

    @Test
    fun withNothingKeptThereIsNothingToDrop() {
        assertThat(stale(UiState(screen = Screen.Rest(RestReason.IDLE)), playing(b))).isFalse()
        assertThat(stale(clock, null)).isFalse()
    }

    @Test
    fun theClockStaysWhileTheNewSongsSceneIsMade() {
        val held = HeldStage()
        val waiting = UiState(screen = playing(b))
        val first = held.shown(clock, waiting, stale = true)
        assertThat(first.screen).isEqualTo(clock.screen)
        // Later passes see the clock as it shows, and keep it until the scene is there.
        assertThat(held.shown(first, waiting, stale = false).screen).isEqualTo(clock.screen)
        val ready = waiting.copy(stage = Fixtures.stage(b, mdpi))
        assertThat(held.shown(first, ready, stale = false)).isSameInstanceAs(ready)
        // Once the stage has come, nothing is held any more.
        assertThat(held.shown(ready, waiting, stale = false)).isSameInstanceAs(waiting)
    }

    @Test
    fun theLandscapeStaysTooAndAChosenOneEndsTheWait() {
        val held = HeldStage()
        val waiting = UiState(screen = playing(b))
        val first = held.shown(landscape, waiting, stale = true)
        assertThat(first.landscape).isEqualTo(landscape.landscape)
        assertThat(first.screen).isEqualTo(landscape.screen)
        val chosen = waiting.copy(landscape = Fixtures.landscape(b, PlayerStatus.PLAYING, MiniPlayer.LEFT))
        assertThat(held.shown(first, chosen, stale = false)).isSameInstanceAs(chosen)
    }

    @Test
    fun troubleAndTheSetupPageGoThroughTheDark() {
        val waiting = UiState(screen = playing(b))
        val offline = onStage.copy(screen = Screen.Rest(RestReason.OFFLINE))
        assertThat(HeldStage().shown(offline, waiting, stale = true)).isSameInstanceAs(waiting)
        val setup = onStage.copy(screen = Screen.Setup(SetupReason.REQUESTED))
        assertThat(HeldStage().shown(setup, waiting, stale = true)).isSameInstanceAs(waiting)
    }

    @Test
    fun theWaitEndsWhenTheMusicStopsAgain() {
        val held = HeldStage()
        val first = held.shown(clock, UiState(screen = playing(b)), stale = true)
        val rest = UiState(screen = Screen.Rest(RestReason.PAUSED, "Wohnzimmer"))
        assertThat(held.shown(first, rest, stale = false)).isSameInstanceAs(rest)
        // Without a stage dropped anew, a song with its scene still to come waits in the dark.
        val waiting = UiState(screen = playing(b))
        assertThat(held.shown(rest, waiting, stale = false)).isSameInstanceAs(waiting)
    }
}
