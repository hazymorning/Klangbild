package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.lights.CoverLightColors
import io.github.hazymorning.klangbild.lights.LightColor
import io.github.hazymorning.klangbild.lights.LightColors
import io.github.hazymorning.klangbild.lights.LightSlot
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.landscape.LandscapeScreen
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.Stage
import kotlinx.datetime.toJavaZoneId
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The screens in the README's pictures, in English; beside each song the colors the lights
 * behind the TV would show (a JSON file). `tools/make_readme_pictures.py` sets them in a room
 * and saves the pictures to docs/screenshots/ when the look changes.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class ReadmeShotsTest {
    @BeforeTest
    fun berlin() {
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone.toJavaZoneId()))
    }

    private val en = TestTexts.english()

    private fun cover(c: SyntheticCover) = Catalog.SCHEME + c.name

    private val polarNight = TrackInfo("Polar Night", listOf("Ida Holm"), "Northern Lights", 2025, 258_000, artworkUrl = cover(SyntheticCover.AURORA))
    private val saltWater = TrackInfo("Salt Water", listOf("Northern Light Collective"), "Tides", 2023, 214_000, artworkUrl = cover(SyntheticCover.SALT_WATER))
    private val desertSong = TrackInfo("Desert Song", listOf("Amal Haddad"), "Cairo Nights", 2016, 289_000, artworkUrl = cover(SyntheticCover.TERRACOTTA))
    private val neonHighway = TrackInfo("Neon Highway", listOf("Lumen Drive"), "Night Drive", 2024, 251_000, artworkUrl = cover(SyntheticCover.NIGHT_DRIVE))
    private val morningFog = TrackInfo("Morning Fog", listOf("Theo Lind"), "Low Hills", 2022, 236_000, artworkUrl = cover(SyntheticCover.MOSS))

    // The first picture's song is the one the promo film opens and ends with: the app's own name
    // by its maker over its tagline, and the film's middle song up next.
    private val klangbild = TrackInfo("Klangbild", listOf("hazymorning"), "See what you\u2019re listening to.", null, 302_000, artworkUrl = cover(SyntheticCover.AURORA))
    private val essentialMix = TrackInfo("Essential Mix, Vol. 1", listOf("Klangbild"), "Essential Mix", 2026, 198_000)

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
        val scene = Fixtures.scene(klangbild)
        shot("now-playing") {
            Stage(Fixtures.model(klangbild, next = essentialMix, positionMs = 95_000L, clock = en.clock(Fixtures.at(21, 47), Fixtures.zone), group = "Living room", scene = scene, t = en))
        }
        coverLights("now-playing", scene, warmth = 1f)
    }

    /** The first picture for a light page: the same song by day on the light wall (the lights are off by day). */
    @Test
    fun nowPlayingLight() {
        val scene = Fixtures.scene(klangbild, appearance = Appearance.LIGHT)
        shot("now-playing-light") {
            Stage(Fixtures.model(klangbild, next = essentialMix, positionMs = 95_000L, clock = en.clock(Fixtures.at(14, 47), Fixtures.zone), group = "Living room", scene = scene, t = en, appearance = Appearance.LIGHT))
        }
    }

    /**
     * The queue's three ways and the landscape, one after another on the same evening: first Salt
     * Water with the queue off, on its own colors (the background "Cover colors").
     */
    @Test
    fun queueOff() {
        val scene = Fixtures.scene(saltWater, coverField = true)
        shot("queue-off") {
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

    /** Then Morning Fog with the queue as a list beside it: the songs that come, each with its small cover. */
    @Test
    fun queueList() {
        val coming = listOf(
            desertSong,
            TrackInfo("Lantern Field", listOf("Ines Moreau"), "Paper Boats", 2023, 221_000, artworkUrl = cover(SyntheticCover.PASTEL)),
            TrackInfo("Snowfield", listOf("Ida Holm"), "Northern Lights", 2025, 280_000, artworkUrl = cover(SyntheticCover.AURORA)),
            TrackInfo("Grey Ridge", listOf("Theo Lind"), "Stone Paths", 2024, 306_000, artworkUrl = cover(SyntheticCover.MIST)),
            TrackInfo("Blue Hour", listOf("Northern Light Collective"), "Tides", 2023, 247_000, artworkUrl = cover(SyntheticCover.SALT_WATER)),
        )
        val scene = Fixtures.scene(morningFog)
        val list = Fixtures.queueList(coming)
        shot("queue-list") {
            Stage(Fixtures.queueModel(morningFog, list, positionMs = 88_000L, clock = en.clock(Fixtures.at(21, 59), Fixtures.zone), group = "Living room", scene = scene, t = en))
        }
    }

    /** The landscape on a deep winter night: a thin moon, a sky full of stars, the campfire on the snow; the mini player bottom left. */
    @Test
    fun landscapeShot() {
        val at = ZonedDateTime.of(2026, 1, 22, 19, 15, 0, 0, Fixtures.zone.toJavaZoneId()).toInstant().toEpochMilli()
        val ui = UiState(
            screen = Screen.Stage(polarNight, PlayerStatus.PAUSED, null),
            stage = Fixtures.stage(polarNight),
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40),
            landscape = Fixtures.landscape(polarNight, PlayerStatus.PAUSED, MiniPlayer.LEFT, "clear-night"),
        )
        val landscapeClock = LandscapeClock(now = { at })
        shot("landscape") {
            LandscapeScreen(ui, ui.landscape!!, landscapeClock, positionOf = { 94_000L }, now = { at }, modifier = Modifier.fillMaxSize())
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
