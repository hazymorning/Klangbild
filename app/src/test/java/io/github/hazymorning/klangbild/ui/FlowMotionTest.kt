package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cover flow in motion, as sequences of stills: the next picture arriving (the strip
 * turns over by one card), a step back, a jump to something new (it rolls in from the end of
 * the strip), a song put in between, the queue changing beside the middle, quick skips (the
 * running turn hurries, the lines of the song skipped past never come up), a skip undone at
 * once, a card that joins during a turn (it waits for the turn to land), and the turn beneath a
 * party's code.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class FlowMotionTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    private val covers = listOf(
        SyntheticCover.TERRACOTTA, SyntheticCover.MOSS, SyntheticCover.NIGHT_DRIVE,
        SyntheticCover.WHITE_SPACE, SyntheticCover.BAUHAUS, SyntheticCover.SALT_WATER, SyntheticCover.NOCTURNE,
    )

    private fun scenes(appearance: Appearance = Appearance.DARK) = covers.mapIndexed { i, c ->
        i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, appearance = appearance, view = StageView.FLOW)
    }.toMap()

    /** Shows [from], then each of [then] at its frame (counted from 1), capturing every [stepMs]. */
    private fun run(name: String, from: FlowData, then: Map<Int, FlowData>, frames: Int = 12, stepMs: Long = 100, partyCode: String? = null) {
        var flow by mutableStateOf(from)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheatreOf(flow, partyCode = partyCode) }
        // A party's code is made off the main thread, then comes up.
        if (partyCode != null) repeat(20) {
            Thread.sleep(20)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("build/screenshots/flow_motion/${name}_00.png")
        for (i in 1..frames) {
            then[i]?.let { flow = it }
            compose.mainClock.advanceTimeBy(stepMs)
            compose.onRoot().captureRoboImage("build/screenshots/flow_motion/${name}_%02d.png".format(i))
        }
    }

    private fun run(name: String, from: FlowData, to: FlowData, frames: Int = 12, stepMs: Long = 100, partyCode: String? = null) =
        run(name, from, mapOf(1 to to), frames, stepMs, partyCode)

    @Test
    fun forward() {
        val s = scenes()
        run("forward", Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2), Fixtures.flow(s, listOf(1, 2, 3, 4, 5), 3))
    }

    @Test
    fun forwardBeneathAPartyCode() {
        // The code stays in the middle card's place; the cards turn beneath it.
        val s = scenes()
        run("party", Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2), Fixtures.flow(s, listOf(1, 2, 3, 4, 5), 3), partyCode = "http://192.168.1.5:8095/?join=Q4WZ8TNP")
    }

    @Test
    fun back() {
        val s = scenes()
        run("back", Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2), Fixtures.flow(s, listOf(0, 1, 2, 3), 1))
    }

    @Test
    fun jump() {
        val s = scenes()
        run("jump", Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2), Fixtures.flow(s, listOf(1, 2, 6), 6))
    }

    @Test
    fun songInBetween() {
        // "Play now": a song from elsewhere comes in, the queue goes on with what stood next.
        val s = scenes()
        run("between", Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2), Fixtures.flow(s, listOf(1, 2, 6, 3), 6), frames = 6, stepMs = 200)
    }

    @Test
    fun queueChanges() {
        val s = scenes()
        run("queue", Fixtures.flow(s, listOf(0, 1, 2), 1), Fixtures.flow(s, listOf(0, 1, 5), 1), frames = 5, stepMs = 200)
    }

    @Test
    fun quickSkips() {
        val s = scenes()
        val start = Fixtures.flow(s, listOf(0, 1, 2, 3), 1)
        run("skips", start, mapOf(1 to Fixtures.flow(s, listOf(0, 1, 2, 3), 2), 3 to Fixtures.flow(s, listOf(1, 2, 3, 4), 3)), frames = 12, stepMs = 200)
    }

    @Test
    fun skipUndone() {
        // On and straight back: the turn hurries, then the lines of the song that stays rise.
        val s = scenes()
        val start = Fixtures.flow(s, listOf(0, 1, 2, 3), 1)
        val stays = Fixtures.flow(s, listOf(0, 1, 2, 3), 2)
        run("undone", start, mapOf(1 to stays, 4 to Fixtures.flow(s, listOf(0, 1, 2, 3), 3), 6 to stays), frames = 16, stepMs = 100)
    }

    @Test
    fun cardJoinsDuringTheTurn() {
        // The card after next is ready a moment later: the turn runs its course, then the card comes.
        val s = scenes()
        val start = Fixtures.flow(s, listOf(0, 1, 2, 3), 1)
        run("joins", start, mapOf(1 to Fixtures.flow(s, listOf(0, 1, 2, 3), 2), 4 to Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2)), frames = 13, stepMs = 200)
    }

    @Test
    fun theQueueRunsOut() {
        // The last song comes: nothing stands to the right any more, and the composition settles in the middle with the turn.
        val s = scenes()
        run("runs_out", Fixtures.flow(s, listOf(1, 2, 3, 4), 3), Fixtures.flow(s, listOf(2, 3, 4), 4), frames = 13, stepMs = 100)
    }

    @Test
    fun cardsArriveBesideALoneCover() {
        // A first song alone, then its queue: the cards roll in and everything moves along to the middle.
        val s = scenes()
        run("arrive", Fixtures.flow(s, listOf(2), 2), Fixtures.flow(s, listOf(2, 3, 4), 2), frames = 10, stepMs = 100)
    }

    @Test
    fun theFirstTurn() {
        // From the first song (nothing played) to the second: a card comes to the left.
        val s = scenes()
        run("first_turn", Fixtures.flow(s, listOf(2, 3, 4), 2), Fixtures.flow(s, listOf(2, 3, 4, 5), 3), frames = 13, stepMs = 100)
    }

    @Test
    fun theQueueIsCleared() {
        // Nothing comes any more: the cards to the right go first, then the cover and the text move together.
        val s = scenes()
        run("cleared", Fixtures.flow(s, listOf(2, 3, 4), 2), Fixtures.flow(s, listOf(2), 2), frames = 12, stepMs = 100)
    }

    @Test
    fun backToTheFirstSong() {
        // A step back to the first song: nothing stands to the left any more.
        val s = scenes()
        run("back_first", Fixtures.flow(s, listOf(2, 3, 4, 5), 3), Fixtures.flow(s, listOf(2, 3, 4), 2), frames = 13, stepMs = 100)
    }

    @Test
    fun forwardOnPaper() {
        val s = scenes(Appearance.LIGHT)
        run("paper", Fixtures.flow(s, listOf(0, 1, 2, 3, 4), 2), Fixtures.flow(s, listOf(1, 2, 3, 4, 5), 3), frames = 6, stepMs = 200)
    }
}
