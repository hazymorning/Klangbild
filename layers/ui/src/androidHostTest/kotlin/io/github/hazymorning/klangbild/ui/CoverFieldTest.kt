package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** Cover colors: the cover's colors as the wall, dark and light, beside a single cover and in Cover Flow, and moving. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class CoverFieldTest {
    private val spiegel = Fixtures.spiegel

    /** Every test cover once, on both walls: the field's range at a glance, standing still. */
    @Test
    fun gallery() {
        val covers = Fixtures.tracks.distinctBy { it.artworkUrl }
        for (appearance in Appearance.entries) for ((i, track) in covers.withIndex()) {
            val scene = Fixtures.scene(track, appearance = appearance, coverField = true)
            captureRoboImage("build/screenshots/cover_field/gallery/${appearance.name.lowercase()}_%02d.png".format(i)) {
                Fixtures.Still {
                    Stage(Fixtures.model(track, next = spiegel, group = "Wohnzimmer", scene = scene))
                }
            }
        }
    }

    /** The night drive's field moving, 12 seconds apart (its start is gallery/dark_00). */
    @Test
    fun moving() {
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        val model = Fixtures.model(track, next = spiegel, group = "Wohnzimmer", scene = Fixtures.scene(track, coverField = true))
        for (i in 1..7) {
            captureRoboImage("build/screenshots/cover_field/motion_%02d.png".format(i)) {
                Fixtures.Still {
                    CompositionLocalProvider(LocalFieldTime provides FieldTime({ i * 12.0 }, false)) { Stage(model) }
                }
            }
        }
    }

    @Test
    fun flow() {
        val strip: List<TrackInfo> = listOf(
            Fixtures.track(SyntheticCover.TERRACOTTA),
            Fixtures.radio,
            Fixtures.track(SyntheticCover.NIGHT_DRIVE),
            Fixtures.track(SyntheticCover.WHITE_SPACE),
            Fixtures.track(SyntheticCover.BAUHAUS),
        )
        for (appearance in Appearance.entries) {
            val scenes = Fixtures.flowScenes(strip, appearance, coverField = true)
            captureRoboImage("build/screenshots/cover_field/flow_${appearance.name.lowercase()}.png") {
                Fixtures.Still {
                    FlowStage(
                        Fixtures.model(strip[2], next = strip[3], group = "Wohnzimmer + 5", scene = scenes[2]),
                        Fixtures.restingCards(scenes, 2),
                    )
                }
            }
        }
    }
}
