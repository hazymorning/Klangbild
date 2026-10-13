package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** The landscape on a 4K panel with the UI at 4K: every edge cut for the panel's pixels. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xxxhdpi")
class Landscape4kTest {
    @Before
    fun berlin() {
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone))
    }

    @Test
    fun autumnAfternoon() {
        val t = java.time.ZonedDateTime.of(2026, 10, 10, 16, 20, 0, 0, Fixtures.zone).toInstant().toEpochMilli()
        val track = Fixtures.track(SyntheticCover.CHAMBER_MUSIC)
        val ui = UiState(
            screen = Screen.Stage(track, PlayerStatus.PLAYING, null),
            stage = Fixtures.stage(track, Fixtures.TV_4K),
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40),
            landscape = Fixtures.landscape(track, PlayerStatus.PLAYING, MiniPlayer.LEFT, "partlycloudy"),
        )
        val clock = LandscapeClock(now = { t })
        captureRoboImage("build/screenshots/landscape/4k/autumn_afternoon.png") {
            Fixtures.Still { LandscapeScreen(ui, ui.landscape!!, clock, positionNow = { 100_000L }, now = { t }, modifier = Modifier.fillMaxSize()) }
        }
    }
}
