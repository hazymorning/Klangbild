package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.LandscapeUi
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.SourceState
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

/** The landscape as the app shows it: the picture of the moment in Berlin with the music's label. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeScreenTest {
    @Before
    fun berlin() {
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone))
    }

    private fun shot(
        name: String,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        side: MiniPlayer = MiniPlayer.LEFT,
        cover: SyntheticCover = SyntheticCover.NIGHT_DRIVE,
        status: PlayerStatus = PlayerStatus.PAUSED,
        weather: String? = "partlycloudy",
    ) {
        val t = java.time.ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, Fixtures.zone).toInstant().toEpochMilli()
        val track = Fixtures.track(cover)
        val ui = UiState(
            screen = Screen.Stage(track, status, null),
            stage = Fixtures.stage(track),
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40, weather = weather),
            landscape = LandscapeUi(track, status, side),
        )
        val clock = LandscapeClock(now = { t })
        captureRoboImage("build/screenshots/landscape/screen/$name.png") {
            Fixtures.Still {
                LandscapeScreen(ui, ui.landscape!!, clock, positionNow = { (track.durationMs ?: 0L) / 3 }, now = { t + 60_000L }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun label() {
        shot("label_left_morning", 10, 10, 8, 10)
        shot("label_left_noon", 10, 10, 13, 0, cover = SyntheticCover.BAUHAUS, status = PlayerStatus.PLAYING)
        shot("label_left_dusk", 10, 10, 18, 50, cover = SyntheticCover.MIST)
        shot("label_left_night", 10, 10, 23, 0)
        shot("label_right_noon", 10, 10, 13, 0, side = MiniPlayer.RIGHT, cover = SyntheticCover.CHAMBER_MUSIC)
        shot("label_right_sunset", 10, 10, 18, 25, side = MiniPlayer.RIGHT)
        shot("label_off", 10, 10, 15, 0, side = MiniPlayer.OFF)
    }
}
