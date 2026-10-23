package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.theme.AndroidGlyphs
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

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
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { 60_000L }, now = { 0L }, glyphs = AndroidGlyphs)) }
        compose.mainClock.advanceTimeBy(2_500)
        assertThat(says("Schnelleinstellungen"), name = "while the stage comes").isFalse()
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(says("Pfeil runter"), name = "a moment later").isTrue()
        assertThat(says("öffnet die\u00A0Schnelleinstellungen"), name = "a moment later").isTrue()
        assertThat(says("Spiegel im Spiegel"), name = "a moment later").isFalse()
        compose.onRoot().captureRoboImage("build/screenshots/hint/down.png")
        compose.mainClock.advanceTimeBy(12_000)
        assertThat(says("Schnelleinstellungen"), name = "after a while").isFalse()
        assertThat(says("Spiegel im Spiegel"), name = "after a while").isTrue()
        // Known now: the stage says nothing about them any more, however long it shows.
        ui = ui.copy(downHint = false)
        compose.mainClock.advanceTimeBy(20_000)
        assertThat(says("Schnelleinstellungen"), name = "once known").isFalse()
    }
}
