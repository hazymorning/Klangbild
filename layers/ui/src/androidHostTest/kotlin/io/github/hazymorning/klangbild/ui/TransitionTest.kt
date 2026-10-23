package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * Motion as a sequence of stills: the track change (the new artwork settles over the old,
 * its light morphs on the wall, the text follows line by line), the next song of the same
 * album (only the text changes), quick skips and a skip undone at once, the pause (the
 * artwork recedes and dims) and buffering.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class TransitionTest {
    @get:Rule
    val compose = composeRule()

    /** Frames are rendered at 960 × 540 (mdpi) to keep the image sequences light. */
    private val MDPI = ScreenSpec(960, 540, 1f)

    @Test
    fun trackChange() {
        val a = Fixtures.stage(Fixtures.track(SyntheticCover.NIGHT_DRIVE), MDPI)
        val b = Fixtures.stage(Fixtures.track(SyntheticCover.MIST), MDPI)
        var stage by mutableStateOf(a)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, PlayerStatus.PLAYING) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("build/screenshots/transition/track_00.png")
        stage = b
        for (i in 1..14) {
            compose.mainClock.advanceTimeBy(100)
            compose.onRoot().captureRoboImage("build/screenshots/transition/track_%02d.png".format(i))
        }
    }

    @Test
    fun sameAlbum() {
        val first = Fixtures.track(SyntheticCover.SALT_WATER)
        val a = Fixtures.stage(first, MDPI)
        val nextSong = first.copy(title = "Ebbe", durationMs = 201_000, key = "Ebbe|" + first.artists.joinToString() + "|" + first.album)
        // Same picture, new track: the scene is rebuilt from the same image.
        val b = io.github.hazymorning.klangbild.app.StageData(
            nextSong,
            io.github.hazymorning.klangbild.render.SceneBuilder.build(
                nextSong.key, io.github.hazymorning.klangbild.art.SyntheticCovers.render(SyntheticCover.SALT_WATER),
                nextSong.artSeed, MDPI,
            ),
        )
        var stage by mutableStateOf(a)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, PlayerStatus.PLAYING) }
        compose.mainClock.advanceTimeBy(100)
        stage = b
        for (i in 1..6) {
            compose.mainClock.advanceTimeBy(200)
            compose.onRoot().captureRoboImage("build/screenshots/transition/album_%02d.png".format(i))
        }
    }

    @Test
    fun quickSkips() {
        // A song skipped past: its change hurries, its lines never come up, the next one follows.
        val a = Fixtures.stage(Fixtures.track(SyntheticCover.NIGHT_DRIVE), MDPI)
        val b = Fixtures.stage(Fixtures.track(SyntheticCover.MIST), MDPI)
        val c = Fixtures.stage(Fixtures.track(SyntheticCover.SALT_WATER), MDPI)
        skips("skips", a, mapOf(1 to b, 4 to c))
    }

    @Test
    fun skipUndone() {
        // On and straight back: the change hurries, then the lines of the song that stays rise.
        val a = Fixtures.stage(Fixtures.track(SyntheticCover.NIGHT_DRIVE), MDPI)
        val b = Fixtures.stage(Fixtures.track(SyntheticCover.MIST), MDPI)
        val c = Fixtures.stage(Fixtures.track(SyntheticCover.SALT_WATER), MDPI)
        skips("undone", a, mapOf(1 to b, 4 to c, 6 to b))
    }

    private fun skips(name: String, from: io.github.hazymorning.klangbild.app.StageData, then: Map<Int, io.github.hazymorning.klangbild.app.StageData>) {
        var stage by mutableStateOf(from)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, PlayerStatus.PLAYING) }
        compose.mainClock.advanceTimeBy(100)
        for (i in 1..20) {
            then[i]?.let { stage = it }
            compose.mainClock.advanceTimeBy(100)
            compose.onRoot().captureRoboImage("build/screenshots/transition/${name}_%02d.png".format(i))
        }
    }

    @Test
    fun pause() {
        val a = Fixtures.stage(Fixtures.track(SyntheticCover.BAUHAUS), MDPI)
        var status by mutableStateOf(PlayerStatus.PLAYING)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(a, status) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("build/screenshots/transition/pause_00.png")
        status = PlayerStatus.PAUSED
        for (i in 1..6) {
            compose.mainClock.advanceTimeBy(120)
            compose.onRoot().captureRoboImage("build/screenshots/transition/pause_%02d.png".format(i))
        }
    }

    @Test
    fun buffering() {
        // The light pulses while buffering (an infinite animation: only a controlled clock can capture it).
        val a = Fixtures.stage(Fixtures.track(SyntheticCover.SALT_WATER), MDPI)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(a, PlayerStatus.BUFFERING) }
        for (i in 0..4) {
            compose.mainClock.advanceTimeBy(450)
            compose.onRoot().captureRoboImage("build/screenshots/transition/buffering_%02d.png".format(i))
        }
    }
}
