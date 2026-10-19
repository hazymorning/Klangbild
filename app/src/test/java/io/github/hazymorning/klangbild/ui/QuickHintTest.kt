package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Until the quick settings have been opened once, the stage tells how: a moment after it comes,
 * the line where "Up next" stands says that arrow down opens them, for a while; then what comes
 * next is back. Once they are known, it never says so again.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class QuickHintTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    private fun says(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theStageTellsHowToOpenTheQuickSettingsUntilTheyAreKnown() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.MOSS), mdpi)
        var ui by mutableStateOf(
            UiState(screen = Screen.Stage(stage.track, PlayerStatus.PLAYING, Fixtures.spiegel), stage = stage, texts = Fixtures.texts, downHint = true),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { 60_000L }, now = { 0L })) }
        compose.mainClock.advanceTimeBy(2_500)
        assertWithMessage("while the stage comes").that(says("Schnelleinstellungen")).isFalse()
        compose.mainClock.advanceTimeBy(2_000)
        assertWithMessage("a moment later").that(says("Pfeil runter")).isTrue()
        assertWithMessage("a moment later").that(says("öffnet die\u00A0Schnelleinstellungen")).isTrue()
        assertWithMessage("a moment later").that(says("Spiegel im Spiegel")).isFalse()
        compose.onRoot().captureRoboImage("build/screenshots/hint/down.png")
        compose.mainClock.advanceTimeBy(12_000)
        assertWithMessage("after a while").that(says("Schnelleinstellungen")).isFalse()
        assertWithMessage("after a while").that(says("Spiegel im Spiegel")).isTrue()
        // Known now: the stage says nothing about them any more, however long it shows.
        ui = ui.copy(downHint = false)
        compose.mainClock.advanceTimeBy(20_000)
        assertWithMessage("once known").that(says("Schnelleinstellungen")).isFalse()
    }
}
