package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.QuickControl
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.LyricsChoice
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.stage.PlayerButtons
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stage laid out as a player: the song's words beside a smaller cover with the song under it,
 * the bar across the screen and the buttons under it; on both walls and the cover's colors,
 * German and English, a button chosen, the song without its words, a lost link.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PlayerStageTest {
    private val en = TestTexts.english()
    private val neon = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
    private val neonLyrics = Fixtures.lyrics(Catalog.neonLyrics)
    private val nightLyrics = Fixtures.lyrics(Catalog.nightLyrics)

    /** The buttons as they stand, [focus] chosen (none: at rest). */
    private fun buttons(t: Texts, focus: QuickItem? = null, lyrics: LyricsChoice? = LyricsChoice.ON): QuickState {
        val rows = QuickMenu.rows(Settings(), QuickContext(music = true, lyrics = lyrics), t).filter { it.item in QuickControl.BUTTONS }
        return QuickState(rows, rows.indexOfFirst { it.item == focus }, compact = true)
    }

    private fun shot(name: String, model: StageModel, buttons: QuickState, t: Texts = Fixtures.texts) =
        captureRoboImage("build/screenshots/player/$name.png") {
            Fixtures.Still(t) {
                Box(Modifier.fillMaxSize()) {
                    Stage(model)
                    PlayerButtons(buttons, model.scene)
                }
            }
        }

    private fun words(appearance: Appearance = Appearance.DARK, coverField: Boolean = false, t: Texts = Fixtures.texts, linkDown: Boolean = false): StageModel {
        val english = t === en
        val track = if (english) Fixtures.night else neon
        return Fixtures.lyricsModel(
            track,
            if (english) nightLyrics else neonLyrics,
            position = if (english) ({ 81_000L }) else ({ 84_000L }),
            appearance = appearance,
            scene = Fixtures.scene(track, appearance = appearance, coverField = coverField, player = true),
            t = t,
            group = if (english) "Living room" else "Wohnzimmer",
            // A player's bar shows as set, beside the words too.
            peek = true,
            linkDown = linkDown,
        )
    }

    @Test fun dark() = shot("lyrics_dark", words(), buttons(Fixtures.texts))

    @Test fun light() = shot("lyrics_light", words(Appearance.LIGHT), buttons(Fixtures.texts))

    @Test fun darkEnglish() = shot("lyrics_dark_en", words(t = en), buttons(en), en)

    @Test fun lightEnglish() = shot("lyrics_light_en", words(Appearance.LIGHT, t = en), buttons(en), en)

    @Test fun coverColors() = shot("lyrics_cover_colors", words(coverField = true), buttons(Fixtures.texts))

    @Test fun coverColorsPaper() = shot("lyrics_cover_colors_paper", words(Appearance.LIGHT, coverField = true), buttons(Fixtures.texts))

    /** Down on the remote: the first button chosen, then the settings. */
    @Test fun lyricsChosen() = shot("chosen_lyrics", words(t = en), buttons(en, QuickItem.LYRICS), en)

    @Test fun settingsChosen() = shot("chosen_settings", words(Appearance.LIGHT, t = en), buttons(en, QuickItem.SETTINGS), en)

    /** The words off: the song beside the cover as in the classic layout, the bar across the screen. */
    @Test
    fun withoutWords() {
        val scene = Fixtures.scene(Fixtures.night, player = true)
        shot("song_dark_en", Fixtures.model(Fixtures.night, next = Fixtures.spiegel, t = en, group = "Living room", scene = scene), buttons(en, lyrics = LyricsChoice.OFF), en)
        val paper = Fixtures.scene(neon, appearance = Appearance.LIGHT, player = true)
        shot("song_light", Fixtures.model(neon, next = Fixtures.spiegel, appearance = Appearance.LIGHT, scene = paper), buttons(Fixtures.texts, lyrics = LyricsChoice.OFF))
    }

    /** Without Music Assistant only the way into the settings stands there. */
    @Test
    fun settingsAlone() {
        val scene = Fixtures.scene(Fixtures.night, player = true)
        shot("settings_alone_en", Fixtures.model(Fixtures.night, next = Fixtures.spiegel, t = en, scene = scene), buttons(en, lyrics = null), en)
    }

    /** The link is lost: the notice stands where the bar would, across the screen. */
    @Test fun linkDown() = shot("link_down", words(linkDown = true), buttons(Fixtures.texts))
}
