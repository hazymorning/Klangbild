package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.ui.Fixtures.spiegel
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** The stage for a range of covers and texts (static, no transition). */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class StageTest {
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

    /** A short word never ends a title line; a long "Up next" line drops its artist rather than cutting it. */
    @Test fun shortWords() = shot(
        "short_words",
        Fixtures.model(
            io.github.hazymorning.klangbild.domain.TrackInfo("Spiegel im Spiegel", listOf("Arvo Pärt"), album = "Alina", durationMs = 557_000),
            next = io.github.hazymorning.klangbild.domain.TrackInfo("Für Alina – Variationen über ein Thema", listOf("Arvo Pärt", "Alexander Malter")),
        ),
    )
}
