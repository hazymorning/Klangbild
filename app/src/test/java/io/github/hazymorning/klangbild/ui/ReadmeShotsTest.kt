package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.VistaUi
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.lights.CoverLightColors
import io.github.hazymorning.klangbild.lights.LightColor
import io.github.hazymorning.klangbild.lights.LightColors
import io.github.hazymorning.klangbild.lights.LightSlot
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.vista.VistaScreen
import io.github.hazymorning.klangbild.vista.VistaClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * The screens in the README's pictures, in English; beside each song the colors the lights
 * behind the TV would show (a JSON file). `tools/make_readme_pictures.py` sets them in a room
 * and saves the pictures to docs/screenshots/ when the look changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class ReadmeShotsTest {
    @Before
    fun berlin() {
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone))
    }

    private val en = TestTexts.english()

    private fun cover(c: SyntheticCover) = Catalogue.SCHEME + c.name

    private val polarNight = TrackInfo("Polar Night", listOf("Ida Holm"), "Northern Lights", 2025, 258_000, artworkUrl = cover(SyntheticCover.AURORA))
    private val saltWater = TrackInfo("Salt Water", listOf("Northern Light Collective"), "Tides", 2023, 214_000, artworkUrl = cover(SyntheticCover.SALT_WATER))
    private val desertSong = TrackInfo("Desert Song", listOf("Amal Haddad"), "Cairo Nights", 2016, 289_000, artworkUrl = cover(SyntheticCover.TERRACOTTA))
    private val neonHighway = TrackInfo("Neon Highway", listOf("Lumen Drive"), "Night Drive", 2024, 251_000, artworkUrl = cover(SyntheticCover.NIGHT_DRIVE))
    private val morningFog = TrackInfo("Morning Fog", listOf("Theo Lind"), "Low Hills", 2022, 236_000, artworkUrl = cover(SyntheticCover.MOSS))

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("build/screenshots/readme/$name.png") { Fixtures.Still(en, content) }

    /** The lights' colors for a picture: left, middle (above the TV), right; linear sRGB and the light's own xy. */
    private fun lights(name: String, colors: LightColors, warmth: Float) {
        val rgb = FloatArray(3)
        val json = LightSlot.entries.joinToString(",\n", "{\"lights\": [\n", "\n]}\n") { slot ->
            colors.color(slot, 0.0, rgb)
            val xy = LightColor.of(rgb, warmth)
            "  {\"slot\": \"${slot.name}\", \"rgb\": [${rgb[0]}, ${rgb[1]}, ${rgb[2]}], \"xy\": [${xy.x}, ${xy.y}], \"dim\": ${LightColor.dimFor(rgb)}}"
        }
        File("build/screenshots/readme/$name.json").apply { parentFile?.mkdirs() }.writeText(json)
    }

    private fun coverLights(name: String, scene: ArtworkScene, warmth: Float) = lights(name, CoverLightColors(scene.field!!, moving = false), warmth)

    /** The first picture: a song at night on the dark wall, the room lit in its colors. */
    @Test
    fun nowPlaying() {
        val scene = Fixtures.scene(polarNight)
        shot("now-playing") {
            Stage(Fixtures.model(polarNight, next = saltWater, positionMs = 94_000L, clock = en.clock(Fixtures.at(21, 47), Fixtures.zone), group = "Living room", scene = scene, t = en))
        }
        coverLights("now-playing", scene, warmth = 1f)
    }

    /**
     * The views, one after another on the same evening: Salt Water follows the first picture's
     * song, on its own colors (the background "Cover colors").
     */
    @Test
    fun singleCover() {
        val scene = Fixtures.scene(saltWater, coverField = true)
        shot("single-cover") {
            CompositionLocalProvider(LocalFieldTime provides FieldTime({ 0.0 }, false)) {
                Stage(Fixtures.model(saltWater, next = neonHighway, positionMs = 71_000L, clock = en.clock(Fixtures.at(21, 51), Fixtures.zone), group = "Living room", scene = scene, t = en))
            }
        }
    }

    /** Then Neon Highway in the cover flow: what played before on the left, what comes on the right. */
    @Test
    fun coverFlow() {
        val strip = listOf(polarNight, saltWater, neonHighway, morningFog, desertSong)
        shot("cover-flow") {
            val scenes = Fixtures.flowScenes(strip)
            FlowStage(
                Fixtures.model(neonHighway, next = morningFog, positionMs = 84_000L, clock = en.clock(Fixtures.at(21, 55), Fixtures.zone), group = "Living room", scene = scenes[2], t = en),
                Fixtures.restingCards(scenes, 2),
            )
        }
    }

    /** The landscape on a deep winter night: a thin moon, a sky full of stars, the campfire on the snow; the mini player bottom left. */
    @Test
    fun windowView() {
        val at = ZonedDateTime.of(2026, 1, 22, 19, 15, 0, 0, Fixtures.zone).toInstant().toEpochMilli()
        val ui = UiState(
            screen = Screen.Stage(polarNight, PlayerStatus.PAUSED, null),
            stage = Fixtures.stage(polarNight),
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40, weather = "clear-night"),
            vista = VistaUi(polarNight, PlayerStatus.PAUSED, MiniPlayer.LEFT),
        )
        val vistaClock = VistaClock(now = { at })
        shot("landscape") {
            VistaScreen(ui, ui.vista!!, vistaClock, positionNow = { 94_000L }, now = { at }, modifier = Modifier.fillMaxSize())
        }
    }

    /** One song twice: on the light wall by day and on the dark wall at night. */
    @Test
    fun dayAndNight() {
        val day = Fixtures.scene(desertSong, appearance = Appearance.LIGHT)
        shot("day") {
            Stage(Fixtures.model(desertSong, next = polarNight, positionMs = 61_000L, clock = en.clock(Fixtures.at(14, 15), Fixtures.zone), group = "Living room", scene = day, t = en, appearance = Appearance.LIGHT))
        }
        val night = Fixtures.scene(desertSong)
        shot("night") {
            Stage(Fixtures.model(desertSong, next = polarNight, positionMs = 61_000L, clock = en.clock(Fixtures.at(22, 30), Fixtures.zone), group = "Living room", scene = night, t = en))
        }
        coverLights("day", day, warmth = 0f)
        coverLights("night", night, warmth = 1f)
    }
}
