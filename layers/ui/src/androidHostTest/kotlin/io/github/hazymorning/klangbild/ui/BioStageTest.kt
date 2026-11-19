package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * A few sentences about the artist in the text column: beside a single cover, in Cover Flow and
 * with the cover edge to edge, on both backgrounds and the cover's colors, German and English, a
 * later page, and the bar for a moment.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class BioStageTest {
    private val en = TestTexts.english()
    private val neon = Fixtures.track(SyntheticCover.NIGHT_DRIVE)

    private fun shot(name: String, model: StageModel, t: Texts = Fixtures.texts) =
        captureRoboImage("build/screenshots/bio/$name.png") { Fixtures.Still(t) { Stage(model) } }

    @Test fun dark() = shot("single_dark", Fixtures.bioModel(neon, Fixtures.neonBio))
    @Test fun light() = shot("single_light_en", Fixtures.bioModel(neon, Fixtures.nightBio, appearance = Appearance.LIGHT, group = "Living room", t = en), en)
    @Test fun coverColors() = shot("single_cover_colors", Fixtures.bioModel(neon, Fixtures.neonBio, scene = Fixtures.scene(neon, coverField = true)))
    @Test fun edgeToEdge() = shot("edge_dark", Fixtures.bioModel(neon, Fixtures.neonBio, scene = Fixtures.scene(neon, edge = true)))

    /** Well into the song, the next page. */
    @Test fun laterPage() = shot("later_page", Fixtures.bioModel(neon, Fixtures.neonBio, position = { 20_000L }))

    /** After a key, the bar comes above the footer, and the page keeps clear of it. */
    @Test fun withBar() = shot("bar", Fixtures.bioModel(neon, Fixtures.neonBio, peek = true))

    @Test
    fun coverFlow() {
        val tracks = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.MOSS).map(Fixtures::track) + neon +
            listOf(SyntheticCover.WHITE_SPACE, SyntheticCover.BAUHAUS).map(Fixtures::track)
        val scenes = Fixtures.flowScenes(tracks)
        val model = Fixtures.bioModel(neon, Fixtures.neonBio, scene = scenes[2])
        captureRoboImage("build/screenshots/bio/flow_dark.png") { Fixtures.Still { FlowStage(model, Fixtures.restingCards(scenes, 2)) } }
    }
}
