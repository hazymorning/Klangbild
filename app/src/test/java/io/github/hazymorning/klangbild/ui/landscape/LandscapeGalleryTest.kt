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
import org.shredzone.commons.suncalc.SunTimes
import java.time.ZonedDateTime
import java.util.TimeZone
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/**
 * The landscape through the year, as the app shows it in Berlin: for each season a day
 * (sunrise, noon, golden hour, dusk, blue hour, night) and the weather's moods. The pictures
 * go to build/screenshots/landscape/gallery/, to be looked at side by side.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeGalleryTest {
    @Before
    fun berlin() {
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone))
    }

    private fun shot(name: String, at: ZonedDateTime, weather: String? = "partlycloudy", cover: SyntheticCover = SyntheticCover.MIST, side: MiniPlayer = MiniPlayer.LEFT) {
        val t = at.toInstant().toEpochMilli()
        val track = Fixtures.track(cover)
        val ui = UiState(
            screen = Screen.Stage(track, PlayerStatus.PAUSED, null),
            stage = Fixtures.stage(track),
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40),
            landscape = Fixtures.landscape(track, PlayerStatus.PAUSED, side, weather),
        )
        val clock = LandscapeClock(now = { t })
        captureRoboImage("build/screenshots/landscape/gallery/$name.png") {
            Fixtures.Still {
                LandscapeScreen(ui, ui.landscape!!, clock, positionNow = { (track.durationMs ?: 0L) / 3 }, now = { t }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    /** A day of [month]/[day]: sunrise, noon, golden hour, dusk, blue hour and the night. */
    private fun day(season: String, month: Int, day: Int, weather: String?, cover: SyntheticCover) {
        val date = ZonedDateTime.of(2026, month, day, 0, 0, 0, 0, Fixtures.zone)
        val sun = SunTimes.compute().on(date).at(52.52, 13.40).execute()
        val rise = sun.rise!!.withZoneSameInstant(Fixtures.zone)
        val set = sun.set!!.withZoneSameInstant(Fixtures.zone)
        val noon = sun.noon!!.withZoneSameInstant(Fixtures.zone)
        shot("${season}_1_sunrise", rise.plusMinutes(12), weather, cover)
        shot("${season}_2_noon", noon, weather, cover)
        shot("${season}_3_golden", set.minusMinutes(50), weather, cover)
        shot("${season}_4_dusk", set.plusMinutes(8), weather, cover)
        shot("${season}_5_blue", set.plusMinutes(38), weather, cover)
        shot("${season}_6_night", date.withHour(23).withMinute(30), weather, cover)
    }

    @Test
    fun spring() = day("1_spring", 4, 26, "sunny", SyntheticCover.MOSS)

    @Test
    fun summer() = day("2_summer", 7, 16, "partlycloudy", SyntheticCover.SALT_WATER)

    @Test
    fun autumn() = day("3_autumn", 10, 10, "partlycloudy", SyntheticCover.MIST)

    @Test
    fun winter() = day("4_winter", 1, 22, "sunny", SyntheticCover.NIGHT_DRIVE)

    @Test
    fun weather() {
        val noon = ZonedDateTime.of(2026, 10, 10, 13, 0, 0, 0, Fixtures.zone)
        shot("5_weather_1_clear", noon, "sunny")
        shot("5_weather_2_partly", noon, "partlycloudy")
        shot("5_weather_3_cloudy", noon, "cloudy")
        shot("5_weather_4_rain", noon, "rainy")
        // A full moon over snow: 3 January 2026.
        shot("5_weather_5_full_moon", ZonedDateTime.of(2026, 1, 3, 22, 0, 0, 0, Fixtures.zone), "clear-night")
        shot("5_weather_6_no_label", noon.withMonth(7).withDayOfMonth(16).withHour(19), "sunny", side = MiniPlayer.OFF)
        shot("5_weather_7_pouring", noon, "pouring")
        shot("5_weather_8_storm", noon, "lightning-rainy")
        shot("5_weather_9_fog", noon.withHour(9), "fog")
        shot("5_weather_a_snow", ZonedDateTime.of(2026, 1, 22, 13, 0, 0, 0, Fixtures.zone), "snowy")
        shot("5_weather_b_snow_night", ZonedDateTime.of(2026, 1, 22, 21, 0, 0, 0, Fixtures.zone), "snowy")
        shot("5_weather_c_rain_dusk", noon.withHour(18).withMinute(50), "rainy")
        shot("5_weather_d_windy", noon, "windy")
    }
}
