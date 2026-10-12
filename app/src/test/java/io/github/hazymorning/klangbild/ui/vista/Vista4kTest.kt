package io.github.hazymorning.klangbild.ui.vista

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.VistaUi
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.vista.VistaClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** The landscape on a 4K panel with the UI at 4K: every edge cut for the panel's pixels. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xxxhdpi")
class Vista4kTest {
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
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40, weather = "partlycloudy"),
            vista = VistaUi(track, PlayerStatus.PLAYING, MiniPlayer.LEFT),
        )
        val clock = VistaClock(now = { t })
        captureRoboImage("build/screenshots/vista/4k/autumn_afternoon.png") {
            Fixtures.Still { VistaScreen(ui, ui.vista!!, clock, positionNow = { 100_000L }, now = { t }, modifier = Modifier.fillMaxSize()) }
        }
    }
}
