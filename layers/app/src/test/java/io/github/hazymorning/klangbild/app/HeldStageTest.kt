package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * After the clock, the setup page or the landscape, a song other than the one the stage kept
 * waits for its own scene, and so does the same song when the wall has changed meanwhile or the
 * queue has gone to or from Cover Flow: the stage left from before never comes up for it (until 2026.10 the song before
 * came up first and the lights took its colors). Meanwhile the clock or the landscape stays,
 * never the dark; over the landscape the song keys act on what plays. A screen that says
 * something is wrong and the setup page go through the dark.
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

    private fun stale(before: UiState, stage: Screen.Stage?, flow: Boolean = false, look: Look = dark, landscape: Boolean = false) =
        HeldStage.stale(before, flow, look::fits, stage, landscape)

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
        val idle = UiState(screen = Screen.Rest(RestReason.IDLE), queue = QueueDisplay.FLOW, flow = flow)
        assertThat(stale(idle, playing(b), flow = true)).isTrue()
        assertThat(stale(idle, playing(a.track), flow = true)).isFalse()
    }

    @Test
    fun aSwitchToCoverFlowMeanwhileNeverBringsBackTheSingleCover() {
        // Cover Flow was chosen over the clock: the single cover kept from before stays away.
        assertThat(stale(clock, playing(a.track), flow = true)).isTrue()
    }

    @Test
    fun withNothingKeptThereIsNothingToDrop() {
        assertThat(stale(UiState(screen = Screen.Rest(RestReason.IDLE)), playing(b))).isFalse()
        assertThat(stale(clock, null)).isFalse()
    }

    @Test
    fun theClockStaysWhileTheNewSongsSceneIsMade() {
        val waiting = UiState(screen = playing(b))
        val first = HeldStage.shown(clock, waiting)
        assertThat(first.screen).isEqualTo(clock.screen)
        // Later passes see the clock as it shows, and keep it until the scene is there.
        assertThat(HeldStage.shown(first, waiting).screen).isEqualTo(clock.screen)
        val ready = waiting.copy(stage = Fixtures.stage(b, mdpi))
        assertThat(HeldStage.shown(first, ready)).isSameInstanceAs(ready)
        // Once the stage has come, nothing is held any more.
        assertThat(HeldStage.shown(ready, waiting)).isSameInstanceAs(waiting)
    }

    @Test
    fun aSongAfterTheClockWaitsBehindItWithNothingKeptToo() {
        // Klangbild came up while nothing played: no stage was ever kept.
        val idle = UiState(screen = Screen.Rest(RestReason.IDLE, "Wohnzimmer"))
        val waiting = UiState(screen = playing(b))
        assertThat(HeldStage.shown(idle, waiting).screen).isEqualTo(idle.screen)
    }

    @Test
    fun theLandscapeStaysTooAndTheSongKeysActOnWhatPlays() {
        val waiting = UiState(screen = playing(b))
        val first = HeldStage.shown(landscape, waiting)
        assertThat(first.landscape).isEqualTo(landscape.landscape)
        assertThat(first.screen).isEqualTo(waiting.screen)
        assertThat(HeldStage.shown(first, waiting).landscape).isEqualTo(landscape.landscape)
        // A landscape chosen meanwhile ends the wait: it shows as it is.
        val chosen = waiting.copy(landscape = Fixtures.landscape(b, PlayerStatus.PLAYING, MiniPlayer.LEFT))
        assertThat(HeldStage.shown(first, chosen)).isSameInstanceAs(chosen)
    }

    @Test
    fun troubleAndTheSetupPageGoThroughTheDark() {
        val waiting = UiState(screen = playing(b))
        val offline = onStage.copy(screen = Screen.Rest(RestReason.OFFLINE))
        assertThat(HeldStage.shown(offline, waiting)).isSameInstanceAs(waiting)
        val setup = onStage.copy(screen = Screen.Setup(SetupReason.REQUESTED))
        assertThat(HeldStage.shown(setup, waiting)).isSameInstanceAs(waiting)
    }

    @Test
    fun theClockComesBackWhenTheMusicStopsAgain() {
        val first = HeldStage.shown(clock, UiState(screen = playing(b)))
        val rest = UiState(screen = Screen.Rest(RestReason.PAUSED, "Wohnzimmer"))
        assertThat(HeldStage.shown(first, rest)).isSameInstanceAs(rest)
        // The next song waits behind it as well.
        assertThat(HeldStage.shown(rest, UiState(screen = playing(b))).screen).isEqualTo(rest.screen)
    }
}
