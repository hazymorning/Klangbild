package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** The cover flow at rest: two cards to each side, dark and light; at night dimmer and warmer, nothing left out. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class FlowTest {
    private val strip: List<TrackInfo> = listOf(
        Fixtures.track(SyntheticCover.TERRACOTTA),
        Fixtures.radio,
        Fixtures.track(SyntheticCover.NIGHT_DRIVE),
        Fixtures.track(SyntheticCover.WHITE_SPACE),
        Fixtures.track(SyntheticCover.BAUHAUS),
    )

    private fun shot(name: String, appearance: Appearance) =
        captureRoboImage("build/screenshots/flow/$name.png") {
            Fixtures.Still {
                val scenes = Fixtures.flowScenes(strip, appearance)
                FlowStage(
                    Fixtures.model(strip[2], next = strip[3], group = "Wohnzimmer + 5", scene = scenes[2]),
                    Fixtures.restingCards(scenes, 2),
                )
            }
        }

    @Test fun dark() = shot("flow_dark", Appearance.DARK)
    @Test fun light() = shot("flow_light", Appearance.LIGHT)
}
