package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.domain.FieldClock
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.landscape.WeatherGlide
import io.github.hazymorning.klangbild.landscape.WeatherMood
import io.github.hazymorning.klangbild.lights.LightSlot
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs
import kotlin.test.Test

class LightTargetsTest {
    private val settings = Settings(roomLights = RoomLights("light.left", "light.middle", "light.right"))
    /** It pours outside. */
    private val ui = UiState(source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40, weather = "pouring"))
    private val noon = LocalDateTime(2026, 10, 10, 13, 0).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    /** The lights' colors (linear sRGB) for the landscape showing [weather] at [t]. */
    private fun lights(weather: WeatherGlide, t: Long): List<Float> {
        val targets = LightTargets(FieldClock { t }, LandscapeClock { t }) { t }
        val colors = targets.target(settings, ui, null, false, LandscapeUi(null, null, MiniPlayer.LEFT, weather))!!.colors
        return LightSlot.entries.flatMap { slot -> FloatArray(3).also { colors.color(slot, 0.0, it) }.toList() }
    }

    private fun near(a: List<Float>, b: List<Float>) = a.zip(b).all { (x, y) -> abs(x - y) < 0.005f }

    @Test
    fun `in the landscape the lights glide into new weather with the picture`() {
        val rain = WeatherMood.of("pouring", null)!!
        val glide = WeatherGlide.still(Weather.FAIR).toward(rain, noon)
        // As the rain begins the picture still shows the fair sky, and so do the lights.
        assertThat(near(lights(glide, noon), lights(WeatherGlide.still(Weather.FAIR), noon))).isTrue()
        assertThat(near(lights(glide, noon), lights(WeatherGlide.still(rain), noon))).isFalse()
        // Twenty minutes on, both have come to the rain.
        val later = noon + 20 * 60_000L
        assertThat(near(lights(glide, later), lights(WeatherGlide.still(rain), later))).isTrue()
    }
}
