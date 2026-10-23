package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.QrCode
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.theme.Fonts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * What is meant to stand in the middle does, measured on the rendered pixels (1080p, 2 px = 1 dp)
 * and on what is seen: the clock's figures from their top to their baseline, every line's ink,
 * the artwork's edges, the progress bar's end, the longest line of text.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class CenteringTest {
    @get:Rule
    val compose = composeRule()

    private val tv = ScreenSpec.TV_1080

    @Test
    fun theRestClockStandsInTheMiddleAndItsLinesUnderIt() {
        // Times whose figures have flat tops: the top of the ink is the top of the figures
        // (round ones overshoot it a little, as they should).
        val cases = listOf(
            RestReason.IDLE to Fixtures.at(17, 57),
            RestReason.PAUSED to Fixtures.at(15, 17),
            RestReason.OFFLINE to Fixtures.at(11, 55),
            RestReason.ENTITY_MISSING to Fixtures.at(7, 57),
        )
        // In German with 24 hours, and in English with 12 (the clock shows the figures alone).
        for (t in listOf(Fixtures.texts, TestTexts.english())) for ((reason, at) in cases) {
            val ink = render {
                CompositionLocalProvider(LocalTexts provides t) {
                    RestScreen(reason, "Wohnzimmer", "media_player.wohnzimer", at, zone = Fixtures.zone)
                }
            }
            val lines = ink.lines()
            val clock = lines.first()
            val case = "$reason in ${t.locale}"
            assertWithMessage("$case: the clock across").that(abs(clock.centerX - 960f)).isAtMost(1.5f)
            assertWithMessage("$case: the clock up and down (figures' top to baseline)").that(abs((clock.top + clock.baseline) / 2f - 540f)).isAtMost(2f)
            for (line in lines.drop(1)) {
                assertWithMessage("$case: a line under the clock, at ${line.top}").that(abs(line.centerX - 960f)).isAtMost(3f)
            }
        }
    }

    @Test
    fun theRestClocksColonStandsInTheMiddleOfItsFiguresAndTheOneAmongThem() {
        val ink = render { Fixtures.Still { RestScreen(RestReason.IDLE, "Wohnzimmer", null, Fixtures.at(18, 40), zone = Fixtures.zone) } }
        val clock = ink.lines().first()
        // "1", "8", ":", "4" and "0", each its own run of ink; the eight reaches as far over the
        // figures' top as below their baseline, so its middle is theirs.
        val (one, eight, colon) = ink.runs(clock.inkTop, clock.inkBottom)
        assertThat(abs((colon.top + colon.bottom) / 2f - (eight.top + eight.bottom) / 2f)).isAtMost(2f)
        // The one keeps no more room to the eight than the eight keeps to the colon.
        assertThat(eight.left - one.right).isAtMost(colon.left - eight.right)
    }

    @Test
    fun theStageHasAsMuchRoomOnEachSide() {
        // A light cover on black: its square is the brightest thing on the left.
        val ink = render { Fixtures.Still { Stage(Fixtures.model(Fixtures.track(SyntheticCover.BAUHAUS), next = Fixtures.spiegel)) } }
        val art = ink.box(0, 0, 960, 1080)
        // The progress bar's track is quiet: found with a lower threshold.
        val right = ink.box(960, 0, 1920, 1080, threshold = 24)
        assertWithMessage("above and below the artwork").that(abs(art.top - (1079 - art.bottom))).isAtMost(1)
        assertWithMessage("left of the artwork, right of the column").that(abs(art.left - (1919 - right.right))).isAtMost(2)
    }

    @Test
    fun theLineSungNowStandsOnTheArtworksMiddle() {
        // The line sung now is set in the title's ink, the others in the quiet one: only it is this bright.
        val words = Fixtures.lyrics("[00:05.00]Die Stadt schläft unter Glas\n[00:09.00]Neonlicht über der A40\n[00:14.00]und ich fahre ohne Ziel")
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        // Cover Flow with its whole strip, so that its column stands where the grid has it.
        val strip = Fixtures.flowScenes(listOf(SyntheticCover.MOSS, SyntheticCover.MIST, SyntheticCover.NIGHT_DRIVE, SyntheticCover.SALT_WATER, SyntheticCover.CONFETTI).map(Fixtures::track))
        for (t in listOf(Fixtures.texts, TestTexts.english())) for (flow in listOf(false, true)) {
            val scene = if (flow) strip[2] else Fixtures.scene(track)
            val model = Fixtures.lyricsModel(track, words, position = { 10_000L }, scene = scene, t = t)
            val ink = render {
                Fixtures.Still(t) {
                    if (flow) io.github.hazymorning.klangbild.ui.flow.FlowStage(model, Fixtures.restingCards(strip, 2)) else Stage(model)
                }
            }
            val layout = scene.layout
            val left = (layout.columnLeft * 2).toInt() - 4
            val sung = ink.lines(left, 1920, threshold = LIT).single()
            val case = "${if (flow) "Cover Flow" else "single cover"} in ${t.locale}"
            // Its cap height and its baseline are as far from the artwork's middle (540) on either side.
            val cap = 30f * io.github.hazymorning.klangbild.ui.theme.Fonts.CAP_HEIGHT * 2f
            assertWithMessage("$case: the sung line's baseline").that(abs(sung.baseline - (540f + cap / 2f))).isAtMost(1.5f)
            // It starts on the column's left edge (pulled left by large type's extra side bearing, a pixel or two).
            assertWithMessage("$case: the sung line's left edge").that(abs(sung.left - layout.columnLeft * 2f)).isAtMost(4f)
        }
    }

    @Test
    fun theQueuesQuietLinesLeadWhatFollowsAtOneDistanceAndTheListKeepsToTheColumn() {
        // A title that takes the list's largest size on one line, and a first row whose small
        // cover is light at its top, so that its ink starts where the row's cap height stands.
        val playing = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        val first = Fixtures.track(SyntheticCover.BAUHAUS)
        val coming = listOf(first) + Fixtures.tracks.filter { it != playing && it != first && it.durationMs != null }
        for (t in listOf(Fixtures.texts, TestTexts.english())) {
            val model = Fixtures.queueModel(playing, Fixtures.queueList(coming), t = t)
            val ink = render { Fixtures.Still(t) { Stage(model) } }
            val layout = model.scene.layout
            val left = (layout.columnLeft * 2).toInt() - 4
            val lines = ink.lines(left, 1920)
            // The status line, the title, artist and album, the bar, its times, "Up next", the rows.
            val bar = lines.indexOfFirst { it.inkBottom - it.inkTop < 12 }
            val (status, title, meta) = lines
            val times = lines[bar + 1]
            val heading = lines[bar + 2]
            val row = lines[bar + 3]
            val case = "in ${t.locale}"
            // The status line leads the song as "Up next" leads the rows: baseline to cap height.
            val titleCap = title.baseline - 34f * Fonts.CAP_HEIGHT * 2f
            assertWithMessage("$case: status line to title against \"Up next\" to the first row")
                .that(abs((titleCap - status.baseline) - (row.inkTop - heading.baseline))).isAtMost(2f)
            assertWithMessage("$case: the status line's cap height on the grid").that(abs(status.inkTop - layout.statusTop * 2f)).isAtMost(2f)
            // The song and the list stand further apart than anything within the song: the bar
            // (its fill, then its times) belongs to the song.
            assertWithMessage("$case: the room before the list against the room above the bar")
                .that(heading.top - times.baseline).isGreaterThan(lines[bar].inkTop - meta.baseline + 8)
            // Nothing of the list reaches past the column's foot, or beyond its right edge.
            val foot = (layout.footerBottom * 2).toInt()
            assertWithMessage("$case: below the column's foot").that(ink.box(left, foot + 1, 1920, 1080, threshold = 24).right).isEqualTo(-1)
            val right = ((layout.columnLeft + layout.columnWidth) * 2).toInt()
            assertWithMessage("$case: right of the column").that(ink.box(right + 2, 0, 1920, 1080, threshold = 24).right).isEqualTo(-1)
            // Every line starts on the column's left edge (the title a pixel or two further left).
            for (line in listOf(status, title, heading, row)) {
                assertWithMessage("$case: a line's left edge at ${line.top}").that(abs(line.left - layout.columnLeft * 2f)).isAtMost(4f)
            }
        }
    }

    @Test
    fun theMenuHasAsMuchRoomOnEachSide() {
        val settings = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.wohnzimmer")
        val ink = render { Fixtures.Still { MenuScreen(Fixtures.menu(io.github.hazymorning.klangbild.config.MenuEntry.APPEARANCE, settings)) } }
        val all = ink.box(0, 0, 1920, 1080)
        assertWithMessage("left of the heading, right of the values").that(abs(all.left - (1919 - all.right))).isAtMost(4)
    }

    @Test
    fun theSetupCodeAndItsTextStandInTheMiddle() {
        val info = SetupInfo(url = "http://192.168.1.23:8765", code = "482913", qr = QrCode.matrix("http://192.168.1.23:8765/?code=482913"))
        for (reason in SetupReason.entries) {
            val ink = render { Fixtures.Still { SetupScreen(reason, info, SetupArt.scene(info, tv)) } }
            val all = ink.box(0, 0, 1920, 1080)
            val card = ink.box(0, 0, 960, 1080)
            assertWithMessage("$reason: left of the code, right of the longest line").that(abs(card.left - (1919 - all.right))).isAtMost(6)
            assertWithMessage("$reason: above and below the code").that(abs(card.top - (1079 - card.bottom))).isAtMost(1)
        }
    }

    // --- measuring ---------------------------------------------------------------------------

    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)

    private fun render(content: @Composable () -> Unit): Ink {
        if (shown == null) compose.setContent { shown?.invoke() }
        shown = content
        compose.waitForIdle()
        return Ink(compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * A line of text: its ink across, its top and its baseline (where most of its columns end), and
     * the rows its ink reaches.
     */
    private class Line(val left: Int, val right: Int, val top: Int, val baseline: Int, val inkTop: Int, val inkBottom: Int) {
        val centerX get() = (left + right + 1) / 2f
    }

    /** The bright pixels of a dark screen (text, artwork, the code). The lamp and the glow stay below. */
    private class Ink(bmp: Bitmap) {
        private val w = bmp.width
        private val h = bmp.height
        private val value = IntArray(w * h)

        init {
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            for (i in px.indices) {
                val p = px[i]
                value[i] = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }

        fun box(x0: Int, y0: Int, x1: Int, y1: Int, threshold: Int = THRESHOLD): Box {
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = -1
            var b = -1
            for (y in y0 until y1) for (x in x0 until x1) if (value[y * w + x] > threshold) {
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
            }
            return Box(l, t, r, b)
        }

        /** Runs of ink across, separated by empty columns, between the rows [top] and [bottom], left to right. */
        fun runs(top: Int, bottom: Int, threshold: Int = THRESHOLD): List<Box> {
            val columns = (0 until w).map { x -> (top..bottom).any { value[it * w + x] > threshold } }
            val out = ArrayList<Box>()
            var x = 0
            while (x < w) {
                if (!columns[x]) {
                    x++
                    continue
                }
                val left = x
                while (x < w && columns[x]) x++
                out += box(left, top, x, bottom + 1, threshold)
            }
            return out
        }

        /** Rows of ink between [x0] and [x1], brighter than [threshold], separated by empty rows, top to bottom. */
        fun lines(x0: Int = 0, x1: Int = w, threshold: Int = THRESHOLD): List<Line> {
            fun lit(i: Int) = value[i] > threshold
            val rows = (0 until h).map { y -> (x0 until x1).any { lit(y * w + it) } }
            val out = ArrayList<Line>()
            var y = 0
            while (y < h) {
                if (!rows[y]) {
                    y++
                    continue
                }
                val top = y
                while (y < h && rows[y]) y++
                val bottom = y - 1
                val box = box(x0, top, x1, bottom + 1, threshold)
                // Where most columns start in the line's upper half and end in its lower half: the
                // top of the figures and the baseline (descenders, round overshoots and the bar
                // of a seven are few).
                val starts = IntArray(h)
                val ends = IntArray(h)
                val middle = (top + bottom) / 2
                for (x in box.left..box.right) {
                    var first = -1
                    var last = -1
                    for (yy in top..bottom) if (lit(yy * w + x)) {
                        if (first < 0) first = yy
                        last = yy
                    }
                    if (first in 0..middle) starts[first]++
                    if (last >= middle) ends[last]++
                }
                out += Line(box.left, box.right, starts.indices.maxBy { starts[it] }, ends.indices.maxBy { ends[it] }, top, bottom)
            }
            return out
        }
    }

    private companion object {
        const val THRESHOLD = 90

        /** Brighter than the quiet ink and the status line: only the title's ink (the line sung now). */
        const val LIT = 200
    }
}
