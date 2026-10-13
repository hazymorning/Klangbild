package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.MenuState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.LightLevel
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsMenu
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.SpeakerGroup
import io.github.hazymorning.klangbild.domain.SpeakerLabel
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** The adjustable parts: the speaker group in the status line, volume, hidden lines, light, menu. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class OptionsTest {
    @get:org.junit.Rule
    val compose = composeRule()

    private val spiegel = Fixtures.spiegel
    private val everywhere = SpeakerLabel.group(SpeakerGroup("Wohnzimmer", listOf("Küche", "Bad", "Büro", "Flur", "Terrasse")), Fixtures.texts)
    private val two = SpeakerLabel.group(SpeakerGroup("Wohnzimmer Lautsprecher", listOf("Küche")), Fixtures.texts)
    private val configured = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.wohnzimmer")

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("build/screenshots/options/$name.png") { Fixtures.Still(content) }

    private fun stage(name: String, model: StageModel) = shot(name) { Stage(model) }

    @Test fun groupOfSix() = stage("stage_group", Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = spiegel, group = everywhere))

    @Test fun groupOfTwo() = stage("stage_group_two", Fixtures.model(Fixtures.track(SyntheticCover.SALT_WATER), next = spiegel, group = two))

    @Test fun groupAndVolumeAlways() = stage(
        "stage_group_volume",
        Fixtures.model(
            Fixtures.track(SyntheticCover.MOSS),
            next = spiegel,
            lead = StageTexts.lead(PlayerStatus.PLAYING, false, false, SpeakerLabel.volume(0.35f, false, Fixtures.texts), everywhere, volumeAlways = true, t = Fixtures.texts),
        ),
    )

    @Test fun volumeFlash() = stage(
        "stage_volume_flash",
        Fixtures.model(
            Fixtures.track(SyntheticCover.BAUHAUS),
            next = spiegel,
            lead = StageTexts.lead(PlayerStatus.PLAYING, false, true, SpeakerLabel.volume(0.4f, false, Fixtures.texts), everywhere, volumeAlways = false, t = Fixtures.texts),
        ),
    )

    @Test fun groupWithoutClock() = stage("stage_group_no_clock", Fixtures.model(Fixtures.track(SyntheticCover.TERRACOTTA), next = spiegel, clock = null, group = everywhere))

    @Test fun onlyTitleAndArtist() = stage(
        "stage_minimal",
        Fixtures.model(Fixtures.track(SyntheticCover.MIST), clock = null, album = false, progress = false),
    )

    @Test fun lightStrong() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.CONFETTI))
        shot("light_strong") { Fixtures.Theatre(stage, PlayerStatus.PLAYING, lightFactor = LightLevel.STRONG.factor) }
    }

    @Test fun lightSubtle() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.CONFETTI))
        shot("light_subtle") { Fixtures.Theatre(stage, PlayerStatus.PLAYING, lightFactor = LightLevel.SUBTLE.factor) }
    }

    @Test fun lightOff() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.CONFETTI))
        shot("light_off") { Fixtures.Theatre(stage, PlayerStatus.PLAYING, lightFactor = LightLevel.OFF.factor) }
    }

    @Test fun restGrouped() = shot("rest_group") {
        RestScreen(RestReason.PAUSED, everywhere, null, Fixtures.at(18, 40), zone = Fixtures.zone)
    }

    private fun menu(entry: MenuEntry, settings: Settings = configured, ctx: MenuContext = MenuContext(LinkState.CONNECTED)) =
        Fixtures.menu(entry, settings, ctx)

    @Test fun menuGroups() = shot("menu_groups") { MenuScreen(Fixtures.menuGroups(cursor = 1)) }

    @Test fun menuGroupsLight() = shot("menu_groups_light") { MenuScreen(Fixtures.menuGroups(cursor = 3), appearance = io.github.hazymorning.klangbild.color.Appearance.LIGHT) }

    @Test fun menuTop() = shot("menu_clock") { MenuScreen(menu(MenuEntry.CLOCK)) }

    /** Lyrics without Music Assistant: dimmed, and its value says what it needs. */
    @Test fun menuLyricsNeeds() = shot("menu_lyrics_needs") { MenuScreen(menu(MenuEntry.LYRICS)) }

    /** No lights chosen yet: both lines dimmed, the explanation says how to set them up. */
    @Test fun menuLightsNeeded() = shot("menu_lights_set_up") { MenuScreen(menu(MenuEntry.LIGHTS)) }

    /** OK on "Information": the groups give way to its settings, the first one in the light. */
    @Test fun menuOpensAGroup() {
        var state by androidx.compose.runtime.mutableStateOf(Fixtures.menuGroups(cursor = 1))
        compose.mainClock.autoAdvance = false
        compose.setContent { Frames { MenuScreen(state) } }
        compose.mainClock.advanceTimeBy(100)
        var frame = 0
        fun grab() = compose.onRoot().captureRoboImage("build/screenshots/options/menu_open_%02d.png".format(frame++))
        grab()
        state = menu(MenuEntry.CLOCK)
        repeat(6) { compose.mainClock.advanceTimeBy(80); grab() }
    }

    @Test fun menuGroup() = shot("menu_group") { MenuScreen(menu(MenuEntry.GROUP)) }

    @Test fun menuLight() = shot("menu_light") {
        MenuScreen(menu(MenuEntry.LIGHT, configured.copy(light = LightLevel.SUBTLE)))
    }

    @Test fun menuBars() = shot("menu_bars") {
        MenuScreen(menu(MenuEntry.LIGHTS, configured.copy(roomLights = io.github.hazymorning.klangbild.config.RoomLights(left = "light.l", right = "light.r"), roomLightLevel = io.github.hazymorning.klangbild.config.RoomLightLevel.P60)))
    }

    @Test fun menuSleep() = shot("menu_sleep") { MenuScreen(menu(MenuEntry.SLEEP_AFTER)) }

    @Test fun menuLandscape() = shot("menu_landscape") {
        MenuScreen(menu(MenuEntry.LANDSCAPE_PLAYING, configured.copy(landscapePlayingSeconds = 1800)))
    }

    /** The list glides through the light when the cursor moves down by one line, then by three. */
    @Test fun menuGlide() {
        var state by androidx.compose.runtime.mutableStateOf(menu(MenuEntry.ALBUM))
        compose.mainClock.autoAdvance = false
        compose.setContent { Frames { MenuScreen(state) } }
        compose.mainClock.advanceTimeBy(100)
        var frame = 0
        fun grab() = compose.onRoot().captureRoboImage("build/screenshots/options/menu_glide_%02d.png".format(frame++))
        grab()
        state = menu(MenuEntry.GROUP)
        repeat(6) { compose.mainClock.advanceTimeBy(70); grab() }
        state = menu(MenuEntry.LYRICS)
        repeat(6) { compose.mainClock.advanceTimeBy(70); grab() }
    }

    /**
     * Down held from "Clock": the list runs on through the light and the explanation goes; let
     * go, the list comes to rest and the explanation of its line comes back.
     */
    @Test fun menuHeld() {
        var state by androidx.compose.runtime.mutableStateOf(menu(MenuEntry.CLOCK))
        compose.mainClock.autoAdvance = false
        compose.setContent { Frames { MenuScreen(state) } }
        compose.mainClock.advanceTimeBy(100)
        var frame = 0
        fun grab() = compose.onRoot().captureRoboImage("build/screenshots/options/menu_held_%02d.png".format(frame++))
        grab()
        // The press, then the remote's repeats every 50 ms.
        state = state.copy(cursor = state.cursor + 1)
        compose.mainClock.advanceTimeBy(400)
        grab()
        repeat(6) { i ->
            state = state.copy(cursor = state.cursor + 1, held = true)
            compose.mainClock.advanceTimeBy(50)
            if (i % 2 == 1) grab()
        }
        state = state.copy(held = false)
        repeat(6) { compose.mainClock.advanceTimeBy(70); grab() }
    }

    @Test fun menuConnection() = shot("menu_connection") {
        MenuScreen(menu(MenuEntry.CONNECTION, Settings(), MenuContext(LinkState.UNCONFIGURED)))
    }

    /**
     * A sequence of frames on the test's own clock. The menu's light is made off the main thread on
     * the real clock, so here it is made at once, as in a still: every run shows the same frames.
     */
    @Composable
    private fun Frames(content: @Composable () -> Unit) = CompositionLocalProvider(LocalStill provides true, content = content)
}
