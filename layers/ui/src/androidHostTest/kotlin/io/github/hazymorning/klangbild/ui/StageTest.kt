package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueuePlace
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.ui.Fixtures.spiegel
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** The stage for a range of covers and texts (static, no transition). */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class StageTest {
    private val en = TestTexts.english()

    private fun shot(name: String, model: StageModel) =
        captureRoboImage("build/screenshots/stage/$name.png") { Fixtures.Still { Stage(model) } }

    @Test fun neon() = shot("neon", Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = spiegel, group = "Wohnzimmer + 5"))
    @Test fun classical() = shot("classical", Fixtures.model(Fixtures.track(SyntheticCover.MIST), next = spiegel))
    @Test fun light() = shot("light_bauhaus", Fixtures.model(Fixtures.track(SyntheticCover.BAUHAUS)))
    @Test fun dark() = shot("dark_japanese", Fixtures.model(Fixtures.track(SyntheticCover.NOCTURNE), next = spiegel))
    @Test fun radio() = shot("radio_generated", Fixtures.model(Fixtures.radio, group = "Küche"))
    @Test fun arabic() = shot("arabic", Fixtures.model(Fixtures.track(SyntheticCover.TERRACOTTA), next = spiegel))
    @Test fun longClassical() = shot("long_classical", Fixtures.model(Fixtures.track(SyntheticCover.CHAMBER_MUSIC), next = spiegel))
    @Test fun lowRes() = shot("lowres_emoji", Fixtures.model(Fixtures.track(SyntheticCover.PASTEL)))
    @Test fun noAlbum() = shot("no_album", Fixtures.model(Fixtures.track(SyntheticCover.MOSS), next = spiegel))
    @Test fun busy() = shot("busy", Fixtures.model(Fixtures.track(SyntheticCover.CONFETTI), next = spiegel))
    @Test fun white() = shot("white", Fixtures.model(Fixtures.track(SyntheticCover.WHITE_SPACE)))
    @Test fun featVersion() = shot("feat_version", Fixtures.model(Fixtures.track(SyntheticCover.SALT_WATER), next = spiegel))

    /** The cover edge to edge, on both backgrounds and on its own colors. */
    @Test fun edgeDark() = edge("edge_neon", SyntheticCover.NIGHT_DRIVE)
    @Test fun edgeLight() = edge("edge_bauhaus_light", SyntheticCover.BAUHAUS, Appearance.LIGHT)
    @Test fun edgeCoverColors() = edge("edge_terracotta_cover_colors", SyntheticCover.TERRACOTTA, coverField = true)

    private fun edge(name: String, cover: SyntheticCover, appearance: Appearance = Appearance.DARK, coverField: Boolean = false) {
        val track = Fixtures.track(cover)
        shot(name, Fixtures.model(track, next = spiegel, group = "Wohnzimmer", scene = Fixtures.scene(track, appearance = appearance, coverField = coverField, edge = true)))
    }

    /** The last line starts with the song's place in the queue, in German and English, and stands alone on the last song. */
    @Test fun trackNumber() = shot("track_number", Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = spiegel, group = "Wohnzimmer", place = QueuePlace(3, 45)))

    @Test fun trackNumberEnglish() = captureRoboImage("build/screenshots/stage/track_number_en.png") {
        Fixtures.Still(en) { Stage(Fixtures.model(Fixtures.track(SyntheticCover.MIST), next = spiegel, group = "Living room", t = en, place = QueuePlace(12, 1240))) }
    }

    @Test fun trackNumberLast() = shot("track_number_last", Fixtures.model(Fixtures.track(SyntheticCover.BAUHAUS), place = QueuePlace(45, 45)))

    /** Track number: Top. The place stands in the top line after the room, the state after it. */
    @Test fun trackNumberTop() = shot("track_number_top", Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = spiegel, group = "Wohnzimmer", topPlace = QueuePlace(3, 45)))

    @Test fun trackNumberTopPaused() = captureRoboImage("build/screenshots/stage/track_number_top_paused_en.png") {
        Fixtures.Still(en) {
            Stage(Fixtures.model(Fixtures.track(SyntheticCover.MIST), next = spiegel, status = PlayerStatus.PAUSED, group = "Living room", t = en, topPlace = QueuePlace(12, 1240)))
        }
    }

    @Test fun trackNumberFlow() {
        val neon = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        val tracks = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.MOSS).map(Fixtures::track) + neon +
            listOf(SyntheticCover.WHITE_SPACE, SyntheticCover.BAUHAUS).map(Fixtures::track)
        val scenes = Fixtures.flowScenes(tracks, Appearance.DARK)
        val model = Fixtures.model(neon, next = tracks[3], group = "Wohnzimmer", scene = scenes[2], place = QueuePlace(3, 5))
        captureRoboImage("build/screenshots/stage/track_number_flow.png") { Fixtures.Still { FlowStage(model, Fixtures.restingCards(scenes, 2)) } }
    }

    /** A short word never ends a title line; a long "Up next" line drops its artist rather than cutting it. */
    @Test fun shortWords() = shot(
        "short_words",
        Fixtures.model(
            io.github.hazymorning.klangbild.domain.TrackInfo("Spiegel im Spiegel", listOf("Arvo Pärt"), album = "Alina", durationMs = 557_000),
            next = io.github.hazymorning.klangbild.domain.TrackInfo("Für Alina – Variationen über ein Thema", listOf("Arvo Pärt", "Alexander Malter")),
        ),
    )
}
