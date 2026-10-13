package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.VistaUi
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.ActionState
import io.github.hazymorning.klangbild.config.QuickAction
import io.github.hazymorning.klangbild.config.QuickKey
import io.github.hazymorning.klangbild.config.RoomLightLevel
import io.github.hazymorning.klangbild.config.FavoriteState
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.vista.VistaScreen
import io.github.hazymorning.klangbild.vista.VistaClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/** The quick settings along the bottom edge: over the stage on black and on paper, and over the landscape. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class QuickBarTest {
    @Before
    fun berlin() {
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone))
    }

    private val withLights = Settings(roomLights = RoomLights(left = "light.links", center = "light.mitte", right = "light.rechts"), roomLightLevel = RoomLightLevel.P50)

    private fun state(focus: QuickItem, ctx: QuickContext, s: Settings = withLights): QuickState {
        val rows = QuickMenu.rows(s, ctx, Fixtures.texts)
        return QuickState(rows, rows.indexOfFirst { it.item == focus })
    }

    private val playing = QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false)

    @Test
    fun overTheStage() {
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        captureRoboImage("build/screenshots/quick/stage_dark.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(track, next = Fixtures.spiegel))
                    QuickBar(state(QuickItem.PICTURE, playing), Appearance.DARK)
                }
            }
        }
        captureRoboImage("build/screenshots/quick/stage_dark_favorite.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(track, next = Fixtures.spiegel))
                    QuickBar(state(QuickItem.FAVORITE, playing.copy(favorite = FavoriteState.DONE, shuffle = true)), Appearance.DARK)
                }
            }
        }
        val light = Fixtures.track(SyntheticCover.BAUHAUS)
        captureRoboImage("build/screenshots/quick/stage_light.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(light, next = Fixtures.spiegel, appearance = Appearance.LIGHT))
                    QuickBar(state(QuickItem.SHUFFLE, playing), Appearance.LIGHT)
                }
            }
        }
        // Without lights and without favorites (no Music Assistant): two entries.
        captureRoboImage("build/screenshots/quick/stage_few.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(track, next = Fixtures.spiegel))
                    QuickBar(state(QuickItem.PICTURE, QuickContext(music = true), Settings()), Appearance.DARK)
                }
            }
        }
    }

    /** Buttons of one's own: they come after the built-in entries; a row too long for the room glides to the chosen one. */
    @Test
    fun withButtonsOfOnesOwn() {
        val buttons = withLights.copy(
            quickActions = listOf(
                QuickAction("Lautsprecher trennen", "script.ungroup_speakers"),
                QuickAction("Kinolicht", "scene.kino"),
                QuickAction("Partymodus", "input_boolean.party"),
            ),
        )
        val ctx = playing.copy(
            actions = mapOf(0 to ActionState.READY, 1 to ActionState.DONE, 2 to ActionState.ON),
        )
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        fun shot(name: String, focus: QuickKey, t: io.github.hazymorning.klangbild.text.Texts = Fixtures.texts) {
            val rows = QuickMenu.rows(buttons, ctx, t)
            captureRoboImage("build/screenshots/quick/$name.png") {
                Fixtures.Still(t) {
                    Box(Modifier.fillMaxSize()) {
                        Stage(Fixtures.model(track, next = Fixtures.spiegel, t = t))
                        QuickBar(QuickState(rows, rows.indexOfFirst { it.key == focus }), Appearance.DARK)
                    }
                }
            }
        }
        shot("buttons_start", QuickKey(QuickItem.PICTURE))
        shot("buttons_first", QuickKey(QuickItem.ACTION, 0))
        shot("buttons_end", QuickKey(QuickItem.ACTION, 2))
        shot("buttons_end_en", QuickKey(QuickItem.ACTION, 2), io.github.hazymorning.klangbild.text.TestTexts.english())
    }

    @Test
    fun overTheWindowView() {
        val t = java.time.ZonedDateTime.of(2026, 10, 10, 13, 0, 0, 0, Fixtures.zone).toInstant().toEpochMilli()
        val track = Fixtures.track(SyntheticCover.BAUHAUS)
        val ui = UiState(
            screen = Screen.Stage(track, PlayerStatus.PLAYING, null),
            stage = Fixtures.stage(track),
            source = SourceState(LinkState.CONNECTED, place = 52.52 to 13.40, weather = "partlycloudy"),
            vista = VistaUi(track, PlayerStatus.PLAYING, MiniPlayer.LEFT),
        )
        val clock = VistaClock(now = { t })
        captureRoboImage("build/screenshots/quick/vista.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    VistaScreen(ui, ui.vista!!, clock, positionNow = { 60_000L }, now = { t }, modifier = Modifier.fillMaxSize())
                    QuickBar(state(QuickItem.PICTURE, playing.copy(vista = true)), Appearance.DARK)
                }
            }
        }
    }
}
