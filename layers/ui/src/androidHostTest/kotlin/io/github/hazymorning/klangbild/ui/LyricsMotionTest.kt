package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.flow.FlowTheater
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import io.github.hazymorning.klangbild.ui.stage.StageTheater
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The song's words in motion, as sequences of stills: the list gliding one line on, a break
 * dimming the line, a jump cross-fading the list, the words coming and going with the stage's
 * text change, a new song's words, and the same in Cover Flow.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class LyricsMotionTest {
    @get:Rule
    val compose = composeRule()

    /** Frames are rendered at 960 × 540 (mdpi) to keep the image sequences light. */
    private val mdpi = ScreenSpec(960, 540, 1f)

    private val neon = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
    private val neonLyrics = Fixtures.lyrics(Catalog.neonLyrics)
    private val salt = Fixtures.track(SyntheticCover.SALT_WATER)
    private val saltWords = Fixtures.lyrics(Catalog.saltWords)

    /** Where the song is (ms), as the progress bar has it. */
    private var position by mutableLongStateOf(80_000L)

    /** The model for a stage as the root builds it: the words of the song that plays follow its position. */
    private fun modelFor(current: () -> StageData): (StageData) -> StageModel = { s ->
        val playing = { if (s.track.key == current().track.key) position else null }
        val words = s.lyrics
        if (words != null) {
            Fixtures.lyricsModel(s.track, words, position = playing, scene = s.scene)
        } else {
            val lead = StageTexts.lead(PlayerStatus.PLAYING, false, volumeFlash = false, volume = null, group = "Wohnzimmer", volumeAlways = false, t = Fixtures.texts)
            val m = Fixtures.model(s.track, next = Fixtures.spiegel, scene = s.scene, lead = lead)
            StageModel(
                scene = m.scene, text = m.text, status = m.status, durationMs = m.durationMs, positionMs = playing, year = m.year,
                progress = m.progress, line = m.line, next = m.next, nextTitle = m.nextTitle, statusLine = m.statusLine,
            )
        }
    }

    @Composable
    private fun Theater(stage: StageData, status: PlayerStatus = PlayerStatus.PLAYING) {
        val latest by rememberUpdatedState(stage)
        val models = remember { modelFor { latest } }
        Fixtures.Still { StageTheater(stage = stage, status = status, modelFor = models) }
    }

    @Composable
    private fun Flow(flow: FlowData) {
        val latest by rememberUpdatedState(flow.stage)
        val models = remember { modelFor { latest } }
        Fixtures.Still { FlowTheater(flow = flow, status = PlayerStatus.PLAYING, modelFor = models) }
    }

    private fun stage(track: io.github.hazymorning.klangbild.domain.TrackInfo, words: io.github.hazymorning.klangbild.domain.Lyrics?) =
        StageData(track, Fixtures.scene(track, mdpi), words)

    /** Captures [frames] frames [stepMs] apart, after [then] at the first. */
    private fun frames(name: String, frames: Int, stepMs: Long, then: () -> Unit) {
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("build/screenshots/lyrics_motion/${name}_00.png")
        then()
        for (i in 1..frames) {
            compose.mainClock.advanceTimeBy(stepMs)
            compose.onRoot().captureRoboImage("build/screenshots/lyrics_motion/${name}_%02d.png".format(i))
        }
    }

    @Test
    fun theListGlidesOneLineOn() {
        // "und fang von vorne an" comes at 1:23.8; it moves into the middle a little before.
        position = 82_000L
        val s = stage(neon, neonLyrics)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        frames("glide", frames = 12, stepMs = 50) { position = 83_550L }
    }

    @Test
    fun quickLinesHandOverWithoutAHitch() {
        // A line every 0.4 s, the song playing on: each glide carries on into the next.
        val quick = Fixtures.lyrics(
            listOf("Eins und zwei", "und drei und vier", "Wir zählen mit", "bis alles stimmt", "Fünf und sechs", "und sieben, acht", "Noch einmal", "von vorn")
                .mapIndexed { i, line -> "[00:%02d.%02d]%s".format(10 + i * 4 / 10, (i * 40) % 100, line) }.joinToString("\n"),
        )
        position = 9_900L
        val s = stage(neon, quick)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        compose.mainClock.advanceTimeBy(100)
        for (i in 0..16) {
            compose.onRoot().captureRoboImage("build/screenshots/lyrics_motion/quick_%02d.png".format(i))
            position += 100
            compose.mainClock.advanceTimeBy(100)
        }
    }

    @Test
    fun aPauseDimsTheWordsAndNothingMoves() {
        position = 84_000L
        val s = stage(neon, neonLyrics)
        var status by mutableStateOf(PlayerStatus.PLAYING)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s, status) }
        frames("pause", frames = 6, stepMs = 120) { status = PlayerStatus.PAUSED }
    }

    @Test
    fun aBreakDimsTheLine() {
        position = 30_000L
        val s = stage(neon, neonLyrics)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        frames("rest", frames = 8, stepMs = 100) { position = 31_700L }
    }

    @Test
    fun aJumpCrossFades() {
        position = 84_000L
        val s = stage(neon, neonLyrics)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        frames("jump", frames = 6, stepMs = 100) { position = 130_000L }
    }

    @Test
    fun theWordsComeWithTheTextChange() {
        position = 84_000L
        var s by mutableStateOf(stage(neon, null))
        val on = s.copy(lyrics = neonLyrics)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        frames("on", frames = 10, stepMs = 100) { s = on }
    }

    @Test
    fun theWordsGoWithTheTextChange() {
        position = 84_000L
        var s by mutableStateOf(stage(neon, neonLyrics))
        val off = s.copy(lyrics = null)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        frames("off", frames = 10, stepMs = 100) { s = off }
    }

    @Test
    fun aNewSongBringsItsWords() {
        position = 84_000L
        var s by mutableStateOf(stage(neon, neonLyrics))
        val next = stage(salt, saltWords)
        compose.mainClock.autoAdvance = false
        compose.setContent { Theater(s) }
        frames("song", frames = 12, stepMs = 100) {
            position = 3_000L
            s = next
        }
    }

    private val flowCovers = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.MOSS, SyntheticCover.NIGHT_DRIVE, SyntheticCover.SALT_WATER, SyntheticCover.BAUHAUS)

    private fun flowScenes() = flowCovers.mapIndexed { i, c ->
        i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, flow = true)
    }.toMap()

    @Test
    fun coverFlowTheWordsCome() {
        position = 84_000L
        val scenes = flowScenes()
        var f by mutableStateOf(Fixtures.flow(scenes, listOf(0, 1, 2, 3, 4), 2))
        val on = f.copy(lyrics = neonLyrics)
        compose.mainClock.autoAdvance = false
        compose.setContent { Flow(f) }
        frames("flow_on", frames = 10, stepMs = 100) { f = on }
    }

    @Test
    fun coverFlowTurnsToTheNextSongWithItsWords() {
        position = 84_000L
        val scenes = flowScenes()
        var f by mutableStateOf(Fixtures.flow(scenes, listOf(0, 1, 2, 3, 4), 2).copy(lyrics = neonLyrics))
        val next = Fixtures.flow(scenes, listOf(0, 1, 2, 3, 4), 3).copy(lyrics = saltWords)
        compose.mainClock.autoAdvance = false
        compose.setContent { Flow(f) }
        frames("flow_song", frames = 14, stepMs = 100) {
            position = 3_000L
            f = next
        }
    }
}
