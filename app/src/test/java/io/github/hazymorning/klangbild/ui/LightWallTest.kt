package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** The light wall by day: covers from white to black, a pause, the rest, the menu and the setup. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LightWallTest {
    private val light = Appearance.LIGHT

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("build/screenshots/light/$name.png") { Fixtures.Still(content) }

    private fun stage(name: String, cover: SyntheticCover) = shot(name) {
        Stage(Fixtures.model(Fixtures.track(cover), next = Fixtures.spiegel, group = "Wohnzimmer + 5", appearance = light))
    }

    @Test fun neon() = stage("stage_neon", SyntheticCover.NIGHT_DRIVE)
    @Test fun white() = stage("stage_white", SyntheticCover.WHITE_SPACE)
    @Test fun dark() = stage("stage_dark", SyntheticCover.NOCTURNE)
    @Test fun bauhaus() = stage("stage_bauhaus", SyntheticCover.BAUHAUS)
    @Test fun lowRes() = stage("stage_lowres", SyntheticCover.PASTEL)
    @Test fun radio() = shot("stage_radio") { Stage(Fixtures.model(Fixtures.radio, group = "Küche", appearance = light)) }

    @Test fun paused() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.TERRACOTTA), appearance = light)
        shot("stage_paused") { Fixtures.Theater(stage, PlayerStatus.PAUSED, group = "Wohnzimmer") }
    }

    @Test fun rest() = shot("rest") {
        RestScreen(RestReason.IDLE, "Wohnzimmer", null, Fixtures.at(10, 12), zone = Fixtures.zone, appearance = light)
    }

    @Test fun menu() = shot("menu") {
        val s = Settings(haUrl = "http://ha.local:8123", haToken = "x", entityId = "media_player.wohnzimmer")
        MenuScreen(Fixtures.menu(MenuEntry.APPEARANCE, s, MenuContext(LinkState.CONNECTED, light)), appearance = light)
    }

    @Test fun setup() = shot("setup") {
        val info = SetupInfo("http://192.168.1.23:8765", "482913", SetupServer.qrMatrix("http://192.168.1.23:8765/?code=482913"))
        SetupScreen(SetupReason.FIRST_RUN, info, SetupArt.scene(info, ScreenSpec.TV_1080, light))
    }
}
