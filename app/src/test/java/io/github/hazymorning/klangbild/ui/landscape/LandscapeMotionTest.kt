package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.ui.composeRule
import io.github.hazymorning.klangbild.landscape.Place
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.Weather
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/**
 * The landscape in motion, as sequences of stills: a few seconds frame by frame (the grass
 * and the crown sway, the clouds drift, nothing jumps), a minute in steps of ten seconds, the
 * time-lapse (a day in two minutes) in steps of five seconds, and the first picture arriving.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class LandscapeMotionTest {
    @get:Rule
    val compose = composeRule()

    private val berlin = Place(52.52, 13.40)

    private fun run(
        name: String,
        hour: Int,
        minute: Int,
        frames: Int,
        stepMs: Long,
        timelapse: Boolean = false,
        cover: Float = 0.6f,
        weather: Weather = Weather(cover = cover),
        month: Int = 10,
        day: Int = 10,
    ) {
        val start = java.time.ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, Fixtures.zone).toInstant().toEpochMilli()
        var wall = start
        val clock = LandscapeClock(now = { wall })
        if (timelapse) clock.timelapse(true)
        val feed = LandscapeFeed(place = { berlin }, weather = { weather }, clock = clock, zone = { Fixtures.zone }, wall = { wall })
        compose.mainClock.autoAdvance = false
        compose.setContent { Landscape(feed, Modifier.fillMaxSize()) }
        // The picture is baked off the main thread: give it the time it takes, then let it fade in.
        repeat(8) {
            Thread.sleep(400)
            compose.mainClock.advanceTimeBy(16)
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/landscape/motion/${name}_00.png")
        for (i in 1..frames) {
            wall += stepMs
            compose.mainClock.advanceTimeBy(stepMs)
            compose.onRoot().captureRoboImage("build/screenshots/landscape/motion/${name}_%02d.png".format(i))
        }
    }

    @Test
    fun breeze() = run("breeze", 15, 30, frames = 12, stepMs = 250)

    @Test
    fun arrival() {
        // The first picture keeps one waiting: the words and the bar wait in the dark, then go as the picture comes up.
        val wall = java.time.ZonedDateTime.of(2026, 10, 10, 15, 30, 0, 0, Fixtures.zone).toInstant().toEpochMilli()
        val feed = LandscapeFeed(place = { berlin }, weather = { Weather(cover = 0.6f) }, clock = LandscapeClock(now = { wall }), zone = { Fixtures.zone }, wall = { wall })
        compose.mainClock.autoAdvance = false
        // On the app's black ground.
        compose.setContent { Box(Modifier.fillMaxSize().background(Color.Black)) { Landscape(feed, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(1_700)
        compose.onRoot().captureRoboImage("build/screenshots/landscape/motion/arrival_00.png")
        repeat(8) {
            Thread.sleep(400)
            compose.mainClock.advanceTimeBy(16)
        }
        for (i in 1..10) {
            compose.mainClock.advanceTimeBy(150)
            compose.onRoot().captureRoboImage("build/screenshots/landscape/motion/arrival_%02d.png".format(i))
        }
    }

    @Test
    fun minute() = run("minute", 17, 40, frames = 6, stepMs = 10_000)

    @Test
    fun timelapse() = run("timelapse", 6, 0, frames = 12, stepMs = 5_000, timelapse = true, cover = 0.3f)

    @Test
    fun snow() = run("snow", 14, 0, frames = 8, stepMs = 250, weather = Weather(cover = 0.95f, snow = 0.7f, wind = 0.25f), month = 1, day = 22)

    @Test
    fun rain() = run("rain", 16, 0, frames = 8, stepMs = 250, weather = Weather(cover = 1f, rain = 0.7f, wind = 0.4f))

    @Test
    fun fog() = run("fog", 9, 30, frames = 6, stepMs = 5_000, weather = Weather(cover = 0.75f, fog = 1f, wind = 0.05f))

    @Test
    fun summerNight() = run("summer_night", 23, 30, frames = 8, stepMs = 300, weather = Weather(cover = 0.1f), month = 7, day = 16)

    @Test
    fun campfire() = run("campfire", 21, 0, frames = 8, stepMs = 200, weather = Weather(cover = 0.3f, wind = 0.5f))

    @Test
    fun gale() = run("gale", 13, 0, frames = 8, stepMs = 400, weather = Weather(cover = 0.55f, wind = 0.9f))
}
