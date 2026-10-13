package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** Every designed state at 1080p, plus 4K samples. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class StatesTest {
    private val spiegel = Fixtures.spiegel

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("build/screenshots/states/$name.png") { Fixtures.Still(content) }

    private fun stage(name: String, model: StageModel) = shot(name) { Stage(model) }

    @Test fun playing() = stage("stage_playing", Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = spiegel, group = "Wohnzimmer + 5"))

    @Test fun paused() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.TERRACOTTA))
        shot("stage_paused") { Fixtures.Theater(stage, PlayerStatus.PAUSED, group = "Wohnzimmer") }
    }

    @Test fun everywhere() = stage("stage_everywhere", Fixtures.model(Fixtures.track(SyntheticCover.MOSS), next = spiegel, group = "Überall"))

    @Test fun volume() = stage(
        "stage_volume",
        Fixtures.model(Fixtures.track(SyntheticCover.SALT_WATER), next = spiegel, lead = "Lautstärke 35\u00A0%"),
    )

    @Test fun muted() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.BAUHAUS))
        shot("stage_muted") { Fixtures.Theater(stage, PlayerStatus.PLAYING, muted = true, group = "Küche") }
    }

    /** Values of one's own after the time; with a long place, those that do not fit beside it are left out. */
    @Test fun values() {
        stage("stage_values", Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = spiegel, group = "Wohnzimmer + 5", values = listOf("Außen 12,3\u00A0°C", "Strom 412\u00A0W")))
        stage(
            "stage_values_crowded",
            Fixtures.model(Fixtures.track(SyntheticCover.MOSS), next = spiegel, group = "Wohnzimmer, Küche und Bad + 5", values = listOf("Außen 12,3\u00A0°C", "Waschmaschine fertig um 21:30")),
        )
        stage("stage_values_no_clock", Fixtures.model(Fixtures.track(SyntheticCover.TERRACOTTA), next = spiegel, clock = null, lead = null, values = listOf("12,3\u00A0°C")))
    }

    @Test fun linkDown() = stage("stage_link_down", Fixtures.model(Fixtures.track(SyntheticCover.MIST), linkDown = true))

    @Test fun minimal() = stage(
        "stage_minimal",
        Fixtures.model(Fixtures.track(SyntheticCover.CONFETTI), clock = null, album = false, progress = false),
    )

    @Test fun restIdle() = shot("rest_idle") {
        RestScreen(RestReason.IDLE, "Wohnzimmer", null, Fixtures.at(10, 12), zone = Fixtures.zone)
    }

    @Test fun restPaused() = shot("rest_paused") {
        RestScreen(RestReason.PAUSED, "Wohnzimmer", null, Fixtures.at(18, 40), zone = Fixtures.zone)
    }

    @Test fun connecting() = shot("rest_connecting") {
        RestScreen(RestReason.CONNECTING, null, null, Fixtures.at(13, 5), zone = Fixtures.zone)
    }

    @Test fun offline() = shot("rest_offline") {
        RestScreen(RestReason.OFFLINE, "Wohnzimmer", null, Fixtures.at(16, 20), zone = Fixtures.zone)
    }

    @Test fun unavailable() = shot("rest_unavailable") {
        RestScreen(RestReason.UNAVAILABLE, "Wohnzimmer", null, Fixtures.at(11, 50), zone = Fixtures.zone)
    }

    @Test fun entityMissing() = shot("rest_entity_missing") {
        RestScreen(RestReason.ENTITY_MISSING, null, "media_player.wohnzimer", Fixtures.at(9, 30), zone = Fixtures.zone)
    }

    private val setupInfo = SetupInfo(
        url = "http://192.168.1.23:8765",
        code = "482913",
        qr = SetupServer.qrMatrix("http://192.168.1.23:8765/?code=482913"),
    )

    @Test fun setupFirstRun() = shot("setup_first_run") {
        SetupScreen(SetupReason.FIRST_RUN, setupInfo, SetupArt.scene(setupInfo, ScreenSpec.TV_1080))
    }

    @Test fun setupAuthFailed() = shot("setup_auth_failed") {
        SetupScreen(SetupReason.AUTH_FAILED, setupInfo, SetupArt.scene(setupInfo, ScreenSpec.TV_1080))
    }
}

/** The same at 3840 × 2160 (xxxhdpi), to check crispness and the true cover scale. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xxxhdpi")
class States4kTest {
    private fun shot(name: String, model: StageModel) =
        captureRoboImage("build/screenshots/states/$name.png") { Fixtures.Still { Stage(model) } }

    @Test fun playing4k() = shot(
        "4k_stage_classical",
        Fixtures.model(Fixtures.track(SyntheticCover.CHAMBER_MUSIC), next = Fixtures.spiegel, screen = Fixtures.TV_4K),
    )

    @Test fun lowRes4k() = shot("4k_stage_lowres", Fixtures.model(Fixtures.track(SyntheticCover.PASTEL), screen = Fixtures.TV_4K))

    @Test fun radio4k() = shot("4k_stage_radio", Fixtures.model(Fixtures.radio, screen = Fixtures.TV_4K))
}
