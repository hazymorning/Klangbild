package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.Place
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.ui.Fixtures
import kotlinx.datetime.toJavaZoneId
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** The landscape at real moments in Berlin: the sky as it was, the season of the date. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeTest {
    private val berlin = Place(52.52, 13.40)

    private fun at(name: String, month: Int, day: Int, hour: Int, minute: Int, cover: Float? = 0.3f) {
        val t = java.time.ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, Fixtures.zone.toJavaZoneId()).toInstant().toEpochMilli()
        val feed = LandscapeFeed(place = { berlin }, weather = { cover?.let { Weather(cover = it) } }, clock = LandscapeClock(now = { t }), zone = { Fixtures.zone }, wall = { t })
        captureRoboImage("build/screenshots/landscape/real/$name.png") {
            Fixtures.Still { Landscape(feed, Modifier.fillMaxSize()) }
        }
    }

    @Test
    fun october() {
        at("oct_0730", 10, 10, 7, 30)
        at("oct_1300", 10, 10, 13, 0)
        at("oct_1800", 10, 10, 18, 0)
        at("oct_1845", 10, 10, 18, 45)
        at("oct_2300", 10, 10, 23, 0)
    }
}
