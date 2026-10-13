package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** Cover colors: the cover's colors as the wall, dark and light, in the single view and the cover flow, and moving. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class CoverFieldTest {
    private val spiegel = Fixtures.spiegel

    private fun stage(name: String, cover: SyntheticCover, appearance: Appearance = Appearance.DARK, at: Double = 0.0) {
        val track = Fixtures.track(cover)
        val scene = Fixtures.scene(track, appearance = appearance, coverField = true)
        captureRoboImage("build/screenshots/cover_field/$name.png") {
            Fixtures.Still {
                CompositionLocalProvider(LocalFieldTime provides FieldTime({ at }, false)) {
                    Stage(Fixtures.model(track, next = spiegel, group = "Wohnzimmer", scene = scene, appearance = appearance))
                }
            }
        }
    }

    @Test fun synthwave() = stage("field_nachtfahrt", SyntheticCover.NIGHT_DRIVE)
    @Test fun sea() = stage("field_salzwasser", SyntheticCover.SALT_WATER)
    @Test fun terracotta() = stage("field_terrakotta", SyntheticCover.TERRACOTTA)
    @Test fun moss() = stage("field_moos", SyntheticCover.MOSS)
    @Test fun bauhaus() = stage("field_bauhaus", SyntheticCover.BAUHAUS)
    @Test fun pale() = stage("field_leerraum", SyntheticCover.WHITE_SPACE)
    @Test fun paper() = stage("field_paper_salzwasser", SyntheticCover.SALT_WATER, Appearance.LIGHT)
    @Test fun paperTerracotta() = stage("field_paper_terrakotta", SyntheticCover.TERRACOTTA, Appearance.LIGHT)

    /** Every test cover once, on both walls: the field's range at a glance. */
    @Test
    fun gallery() {
        val covers = Fixtures.tracks.distinctBy { it.artworkUrl }
        for (appearance in Appearance.entries) for ((i, track) in covers.withIndex()) {
            val scene = Fixtures.scene(track, appearance = appearance, coverField = true)
            captureRoboImage("build/screenshots/cover_field/gallery/${appearance.name.lowercase()}_%02d.png".format(i)) {
                Fixtures.Still {
                    Stage(Fixtures.model(track, next = spiegel, group = "Wohnzimmer", scene = scene, appearance = appearance))
                }
            }
        }
    }

    @Test
    fun moving() {
        for (i in 0..7) stage("motion_%02d".format(i), SyntheticCover.NIGHT_DRIVE, at = i * 12.0)
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
                        Fixtures.model(strip[2], next = strip[3], group = "Wohnzimmer + 5", scene = scenes[2], appearance = appearance),
                        Fixtures.restingCards(scenes, 2),
                    )
                }
            }
        }
    }
}
