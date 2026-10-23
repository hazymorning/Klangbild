package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.theme.AndroidGlyphs
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The whole root: a track change must stay inside the stage (no fade through black),
 * while stage → rest fades through the dark.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class RootTransitionTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun trackChangeAndRest() {
        val a = Fixtures.stage(Fixtures.track(SyntheticCover.MOSS), mdpi)
        val b = Fixtures.stage(Fixtures.track(SyntheticCover.CONFETTI), mdpi)
        fun stageUi(s: io.github.hazymorning.klangbild.app.StageData) =
            UiState(screen = Screen.Stage(s.track, PlayerStatus.PLAYING, null), stage = s)
        var ui by mutableStateOf(stageUi(a))
        compose.mainClock.autoAdvance = false
        compose.setContent { KlangbildRoot(ui, mdpi, RootHooks(positionOf = { 60_000L }, now = { 0L }, glyphs = AndroidGlyphs)) }
        compose.mainClock.advanceTimeBy(2_500) // entrance fade
        compose.onRoot().captureRoboImage("build/screenshots/root/00_a.png")
        ui = stageUi(b)
        for (i in 1..4) {
            compose.mainClock.advanceTimeBy(900)
            compose.onRoot().captureRoboImage("build/screenshots/root/0${i}_change.png")
        }
        ui = ui.copy(screen = Screen.Rest(RestReason.IDLE, "Wohnzimmer"))
        for (i in 5..8) {
            compose.mainClock.advanceTimeBy(600)
            compose.onRoot().captureRoboImage("build/screenshots/root/0${i}_rest.png")
        }
    }
}
