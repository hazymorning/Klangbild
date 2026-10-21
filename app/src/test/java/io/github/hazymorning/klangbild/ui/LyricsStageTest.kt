package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.FavoriteState
import io.github.hazymorning.klangbild.config.LyricsChoice
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/**
 * The song's words in the text column: beside a single cover and in Cover Flow, on both walls
 * and the cover's colors, German and English; a break in the singing, the first line waiting,
 * the bar for a moment, long lines, other scripts, a lost link, and the quick settings' Lyrics
 * entry over them.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LyricsStageTest {
    private val en = TestTexts.english()
    private val neon = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
    private val neonLyrics = Fixtures.lyrics(Catalog.neonLyrics)
    private val nightLyrics = Fixtures.lyrics(Catalog.nightLyrics)

    private fun shot(name: String, model: StageModel, t: Texts = Fixtures.texts) =
        captureRoboImage("build/screenshots/lyrics/$name.png") { Fixtures.Still(t) { Stage(model) } }

    /** Cover Flow with two cards on each side, the song in the middle. */
    private fun flowShot(name: String, track: TrackInfo, words: io.github.hazymorning.klangbild.domain.Lyrics, appearance: Appearance, t: Texts = Fixtures.texts, coverField: Boolean = false) {
        val tracks = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.MOSS).map(Fixtures::track) + track +
            listOf(SyntheticCover.WHITE_SPACE, SyntheticCover.BAUHAUS).map(Fixtures::track)
        val scenes = Fixtures.flowScenes(tracks, appearance, coverField = coverField)
        val model = Fixtures.lyricsModel(track, words, appearance = appearance, flow = true, scene = scenes[2], t = t, group = if (t === en) "Living room" else "Wohnzimmer")
        captureRoboImage("build/screenshots/lyrics/$name.png") { Fixtures.Still(t) { FlowStage(model, Fixtures.restingCards(scenes, 2)) } }
    }

    @Test fun dark() = shot("single_dark", Fixtures.lyricsModel(neon, neonLyrics))

    @Test fun light() = shot("single_light", Fixtures.lyricsModel(neon, neonLyrics, appearance = Appearance.LIGHT))

    @Test fun darkEnglish() = shot("single_dark_en", Fixtures.lyricsModel(Fixtures.night, nightLyrics, position = { 81_000L }, t = en, group = "Living room"), en)

    @Test fun lightEnglish() = shot("single_light_en", Fixtures.lyricsModel(Fixtures.night, nightLyrics, position = { 81_000L }, appearance = Appearance.LIGHT, t = en, group = "Living room"), en)

    @Test fun coverColors() = shot("single_cover_colors", Fixtures.lyricsModel(neon, neonLyrics, coverField = true))

    @Test fun coverColorsPaper() = shot("single_cover_colors_paper", Fixtures.lyricsModel(neon, neonLyrics, appearance = Appearance.LIGHT, coverField = true))

    @Test fun flowDark() = flowShot("flow_dark", neon, neonLyrics, Appearance.DARK)

    @Test fun flowLight() = flowShot("flow_light", neon, neonLyrics, Appearance.LIGHT)

    @Test fun flowDarkEnglish() = flowShot("flow_dark_en", Fixtures.night, nightLyrics, Appearance.DARK, en)

    @Test fun flowLightEnglish() = flowShot("flow_light_en", Fixtures.night, nightLyrics, Appearance.LIGHT, en)

    @Test fun flowCoverColors() = flowShot("flow_cover_colors", neon, neonLyrics, Appearance.DARK, coverField = true)

    /** A break in the singing (the guitar takes over): the line sung last stays in the middle, unlit. */
    @Test fun rest() = shot("rest", Fixtures.lyricsModel(neon, neonLyrics, position = { 36_000L }))

    /** Before the first line: it waits in the middle, unlit. */
    @Test fun intro() = shot("intro", Fixtures.lyricsModel(neon, neonLyrics, position = { 5_000L }))

    /** A key brings the progress bar for a moment, above the song; the words make room for it. */
    @Test fun barForAMoment() = shot("bar", Fixtures.lyricsModel(neon, neonLyrics, peek = true))

    /** The link is lost: the notice stands where the bar would. */
    @Test fun linkDown() = shot("link_down", Fixtures.lyricsModel(neon, neonLyrics, linkDown = true))

    /** Long lines: the whole song a size smaller. */
    @Test fun longLines() = shot("long_lines", Fixtures.lyricsModel(Fixtures.track(SyntheticCover.MOSS), Fixtures.lyrics(Catalog.longLyrics), position = { 17_000L }))

    @Test fun japanese() = shot("japanese", Fixtures.lyricsModel(Fixtures.track(SyntheticCover.NOCTURNE), Fixtures.lyrics(Catalog.nightJapaneseLyrics), position = { 15_000L }))

    @Test fun arabic() = shot("arabic", Fixtures.lyricsModel(Fixtures.track(SyntheticCover.TERRACOTTA), Fixtures.lyrics(Catalog.arabicLyrics), position = { 15_000L }))

    /**
     * The quick settings' Lyrics entry over the words, and its quiet On for a song without them,
     * chosen and at rest.
     */
    @Test
    fun quickSettings() {
        fun quick(focus: QuickItem, lyrics: LyricsChoice, t: Texts): QuickState {
            val rows = QuickMenu.rows(Settings(), QuickContext(music = true, favorite = FavoriteState.NONE, shuffle = false, lyrics = lyrics), t)
            return QuickState(rows, rows.indexOfFirst { it.item == focus })
        }
        captureRoboImage("build/screenshots/lyrics/quick_on.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.lyricsModel(neon, neonLyrics))
                    QuickBar(quick(QuickItem.LYRICS, LyricsChoice.ON, Fixtures.texts), Appearance.DARK)
                }
            }
        }
        val noWords = Fixtures.model(Fixtures.track(SyntheticCover.NIGHT_DRIVE), next = Fixtures.spiegel)
        captureRoboImage("build/screenshots/lyrics/quick_muted.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(noWords)
                    QuickBar(quick(QuickItem.LYRICS, LyricsChoice.NONE, Fixtures.texts), Appearance.DARK)
                }
            }
        }
        captureRoboImage("build/screenshots/lyrics/quick_muted_rest.png") {
            Fixtures.Still {
                Box(Modifier.fillMaxSize()) {
                    Stage(noWords)
                    QuickBar(quick(QuickItem.QUEUE, LyricsChoice.NONE, Fixtures.texts), Appearance.DARK)
                }
            }
        }
        captureRoboImage("build/screenshots/lyrics/quick_muted_en.png") {
            Fixtures.Still(en) {
                Box(Modifier.fillMaxSize()) {
                    Stage(Fixtures.model(Fixtures.night, next = Fixtures.spiegel, t = en, appearance = Appearance.LIGHT))
                    QuickBar(quick(QuickItem.LYRICS, LyricsChoice.NONE, en), Appearance.LIGHT)
                }
            }
        }
    }
}
