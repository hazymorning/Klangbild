package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The queue's way just chosen is on its way from the press until the state that shows it: Cover
 * Flow once its strip is there, Off and the list once the single cover's stage is. Only while a
 * song is on stage; over the clock, the setup page or a screen that says something is wrong
 * nothing is made, and what comes later doesn't count as a switch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class QueueSwitchTest {
    private val mdpi = ScreenSpec(960, 540, 1f)
    private val a = Fixtures.stage(Fixtures.track(SyntheticCover.MOSS), mdpi)
    private val b = Fixtures.stage(Fixtures.track(SyntheticCover.CONFETTI), mdpi)

    private fun playing(t: TrackInfo) = Screen.Stage(t, PlayerStatus.PLAYING, null)

    /** The single cover's stage for [s] (Off, or the list beside it). */
    private fun single(s: StageData = a) = UiState(screen = playing(s.track), stage = s)

    /** Cover Flow's strip around [s]. */
    private fun strip(s: StageData = a) = UiState(screen = playing(s.track), queue = QueueDisplay.FLOW, flow = FlowData(listOf(FlowEntry(0L, s.track, s.scene)), 0))

    private val switch = QueueSwitch()

    private fun onItsWay(ready: UiState, setting: QueueDisplay, pending: QueueDisplay? = null, onStage: Boolean = true) =
        switch.onItsWay(ready, onStage, setting, pending)

    @Test
    fun `to Cover Flow it is on its way until the strip is there`() {
        assertThat(onItsWay(single(), QueueDisplay.OFF)).isNull()
        switch.chosen(QueueDisplay.FLOW)
        // The single cover stays while the middle card's scene is made.
        assertThat(onItsWay(single(), QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        assertThat(onItsWay(single(), QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        assertThat(onItsWay(strip(), QueueDisplay.FLOW)).isNull()
        assertThat(onItsWay(strip(), QueueDisplay.FLOW)).isNull()
    }

    @Test
    fun `from Cover Flow it is on its way until the single cover's stage is there`() {
        switch.chosen(QueueDisplay.OFF)
        assertThat(onItsWay(strip(), QueueDisplay.OFF)).isEqualTo(QueueDisplay.OFF)
        assertThat(onItsWay(single(), QueueDisplay.OFF)).isNull()
    }

    @Test
    fun `between Off and the list nothing is made, so it is there in the same state`() {
        switch.chosen(QueueDisplay.LIST)
        assertThat(onItsWay(single(), QueueDisplay.LIST)).isNull()
        switch.chosen(QueueDisplay.OFF)
        assertThat(onItsWay(single(), QueueDisplay.OFF)).isNull()
    }

    @Test
    fun `a choice still waiting in the quick settings counts from the press`() {
        assertThat(onItsWay(single(), QueueDisplay.OFF, pending = QueueDisplay.LIST)).isEqualTo(QueueDisplay.LIST)
        assertThat(onItsWay(single(), QueueDisplay.OFF, pending = QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        // Stepped round to the way that shows: nothing is on its way.
        assertThat(onItsWay(single(), QueueDisplay.OFF, pending = QueueDisplay.OFF)).isNull()
        // Taken: the setting has it, still on its way until the strip is there.
        switch.chosen(QueueDisplay.FLOW)
        assertThat(onItsWay(single(), QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        assertThat(onItsWay(strip(), QueueDisplay.FLOW)).isNull()
    }

    @Test
    fun `picked again before it showed, the newest choice counts`() {
        switch.chosen(QueueDisplay.FLOW)
        assertThat(onItsWay(single(), QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        // Stepped on in the quick settings while Cover Flow is still being made.
        assertThat(onItsWay(single(), QueueDisplay.FLOW, pending = QueueDisplay.OFF)).isEqualTo(QueueDisplay.OFF)
        // Round to Cover Flow again: still on its way.
        assertThat(onItsWay(single(), QueueDisplay.FLOW, pending = QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        // Taken as Off, which the single cover still shows: there at once.
        switch.chosen(QueueDisplay.OFF)
        assertThat(onItsWay(single(), QueueDisplay.OFF)).isNull()
        // From Cover Flow to Off, then the list before the stage came: the list is on its way.
        switch.chosen(QueueDisplay.OFF)
        assertThat(onItsWay(strip(), QueueDisplay.OFF)).isEqualTo(QueueDisplay.OFF)
        switch.chosen(QueueDisplay.LIST)
        assertThat(onItsWay(strip(), QueueDisplay.LIST)).isEqualTo(QueueDisplay.LIST)
        assertThat(onItsWay(single(), QueueDisplay.LIST)).isNull()
    }

    @Test
    fun `a new song meanwhile ends it once its stage shows the way chosen`() {
        switch.chosen(QueueDisplay.FLOW)
        assertThat(onItsWay(single(a), QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        // The next song plays; the strip around it is still being made.
        assertThat(onItsWay(single(a).copy(screen = playing(b.track)), QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        assertThat(onItsWay(strip(b), QueueDisplay.FLOW)).isNull()
    }

    @Test
    fun `over the clock, a screen that says something is wrong or the setup page nothing is on its way`() {
        for (screen in listOf(Screen.Rest(RestReason.PAUSED, "Wohnzimmer"), Screen.Rest(RestReason.OFFLINE), Screen.Setup(SetupReason.REQUESTED))) {
            val switch = QueueSwitch()
            switch.chosen(QueueDisplay.FLOW)
            assertThat(switch.onItsWay(single().copy(screen = screen), false, QueueDisplay.FLOW, null)).isNull()
            // Not even a choice still waiting: nothing is being made.
            assertThat(switch.onItsWay(single().copy(screen = screen), false, QueueDisplay.OFF, QueueDisplay.FLOW)).isNull()
            // The song that comes later waits behind the clock like any other: no switch.
            assertThat(switch.onItsWay(UiState(screen = playing(a.track), queue = QueueDisplay.FLOW), true, QueueDisplay.FLOW, null)).isNull()
        }
    }

    @Test
    fun `a landscape held while the stage is made counts as on stage`() {
        // Chosen over the landscape, which leaves: the stage it kept behind it is let go, and the
        // landscape stays in front until the strip is there.
        val over = single().copy(landscape = Fixtures.landscape(a.track, PlayerStatus.PLAYING, MiniPlayer.LEFT))
        assertThat(onItsWay(over, QueueDisplay.OFF, pending = QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        switch.chosen(QueueDisplay.FLOW)
        val waiting = UiState(screen = playing(a.track), queue = QueueDisplay.FLOW)
        assertThat(HeldStage.shown(over, waiting).landscape).isNotNull()
        assertThat(onItsWay(waiting, QueueDisplay.FLOW)).isEqualTo(QueueDisplay.FLOW)
        assertThat(onItsWay(strip(), QueueDisplay.FLOW)).isNull()
    }

    @Test
    fun `Cover Flow stored when the app starts is no switch`() {
        // The stored settings come while the app still connects.
        switch.chosen(QueueDisplay.FLOW)
        assertThat(onItsWay(UiState(), QueueDisplay.FLOW, onStage = false)).isNull()
        // The first song then waits for its strip as any song does.
        assertThat(onItsWay(UiState(screen = playing(a.track), queue = QueueDisplay.FLOW), QueueDisplay.FLOW)).isNull()
        assertThat(onItsWay(strip(), QueueDisplay.FLOW)).isNull()
    }
}
