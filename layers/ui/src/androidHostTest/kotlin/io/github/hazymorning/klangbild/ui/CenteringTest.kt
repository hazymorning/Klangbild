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
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.QrCode
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.ui.stage.ROW_COVER_CORNER
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.theme.AndroidGlyphs
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.LocalGlyphs
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test

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
                CompositionLocalProvider(LocalTexts provides t, LocalGlyphs provides AndroidGlyphs) {
                    RestScreen(reason, "Wohnzimmer", "media_player.wohnzimer", at, zone = Fixtures.zone)
                }
            }
            val lines = ink.lines()
            val clock = lines.first()
            val case = "$reason in ${t.locale}"
            assertThat(abs(clock.centerX - 960f), name = "$case: the clock across").isLessThanOrEqualTo(1.5f)
            assertThat(abs((clock.top + clock.baseline) / 2f - 540f), name = "$case: the clock up and down (figures' top to baseline)").isLessThanOrEqualTo(2f)
            for (line in lines.drop(1)) {
                assertThat(abs(line.centerX - 960f), name = "$case: a line under the clock, at ${line.top}").isLessThanOrEqualTo(3f)
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
        assertThat(abs((colon.top + colon.bottom) / 2f - (eight.top + eight.bottom) / 2f)).isLessThanOrEqualTo(2f)
        // The one keeps no more room to the eight than the eight keeps to the colon.
        assertThat(eight.left - one.right).isLessThanOrEqualTo(colon.left - eight.right)
    }

    @Test
    fun theStageHasAsMuchRoomOnEachSide() {
        // A light cover on black: its square is the brightest thing on the left.
        val ink = render { Fixtures.Still { Stage(Fixtures.model(Fixtures.track(SyntheticCover.BAUHAUS), next = Fixtures.spiegel)) } }
        val art = ink.box(0, 0, 960, 1080)
        // The progress bar's track is quiet: found with a lower threshold.
        val right = ink.box(960, 0, 1920, 1080, threshold = 24)
        assertThat(abs(art.top - (1079 - art.bottom)), name = "above and below the artwork").isLessThanOrEqualTo(1)
        assertThat(abs(art.left - (1919 - right.right)), name = "left of the artwork, right of the column").isLessThanOrEqualTo(2)
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
            assertThat(abs(sung.baseline - (540f + cap / 2f)), name = "$case: the sung line's baseline").isLessThanOrEqualTo(1.5f)
            // It starts on the column's left edge (pulled left by large type's extra side bearing, a pixel or two).
            assertThat(abs(sung.left - layout.columnLeft * 2f), name = "$case: the sung line's left edge").isLessThanOrEqualTo(4f)
        }
    }

    @Test
    fun theQueuesSongStandsInTheMiddleItsSpacesGrowAndTheListEndsWithTheCover() {
        // Rows in Latin letters, so that each of a row's two lines has ink of its own right of the small covers.
        val coming = Catalog.cairoNights + Catalog.northernHours
        for (t in listOf(Fixtures.texts, TestTexts.english())) for (bar in listOf(true, false)) {
            val model = Fixtures.queueModel(Catalog.desert, Fixtures.queueList(coming), progress = bar, t = t)
            val ink = render { Fixtures.Still(t) { Stage(model) } }
            val layout = model.scene.layout
            val case = "${if (bar) "with" else "without"} the bar in ${t.locale}"
            val left = (layout.columnLeft * 2).toInt() - 4
            val (status, title) = ink.lines(left, 1920)
            assertThat(abs(status.inkTop - layout.statusTop * 2f), name = "$case: the status line's cap height on the grid").isLessThanOrEqualTo(2f)
            for (line in listOf(status, title)) {
                assertThat(abs(line.left - layout.columnLeft * 2f), name = "$case: a line's left edge at ${line.top}").isLessThanOrEqualTo(4f)
            }
            // Right of the small covers: the song's lines (the status line, the title, artist and
            // album, the bar and its times), then each row's title and artists.
            val lines = ink.lines(((layout.columnLeft + layout.rowCover + 6f) * 2).toInt(), 1920)
            val song = if (bar) 5 else 3
            val meta = lines[2]
            val last = lines[song - 1]
            val rows = lines.drop(song).chunked(2)
            assertThat(rows.size, name = "$case: rows").isEqualTo(if (bar) 3 else 4)
            // The song in the middle between the status line and the list: from the status line's
            // baseline to the title's top as far as from the song's last line to the first small
            // cover (with the bar its times, figures whose ink ends on the baseline: the bar of a four
            // ends above). A small cover stands a corner radius above its title's cap height.
            fun coverTop(row: List<Line>) = row[0].baseline - 17f * Fonts.CAP_HEIGHT * 2f - ROW_COVER_CORNER * 2f
            val above = title.inkTop - status.baseline
            val below = coverTop(rows[0]) - (if (bar) last.inkBottom else last.baseline)
            assertThat(abs(above - below), name = "$case: above the song against below it").isLessThanOrEqualTo(3f)
            // The spaces grow: within a row, between two rows, from the song to the list (more
            // than above the bar, which belongs to the song).
            val within = rows.maxOf { (words, artists) -> artists.inkTop - words.baseline }
            val between = rows.zipWithNext { a, b -> b[0].inkTop - a[1].baseline }
            val part = rows[0][0].inkTop - last.baseline
            assertThat(between.min().toFloat(), name = "$case: between rows against within one").isGreaterThan(2.5f * within)
            assertThat(part.toFloat(), name = "$case: from the song to the list against between rows").isGreaterThan(1.6f * between.max())
            if (bar) assertThat(part.toFloat(), name = "$case: from the song to the list against above the bar").isGreaterThan(1.5f * (lines[3].inkTop - meta.baseline))
            // One step from row to row.
            val steps = rows.zipWithNext { a, b -> b[0].baseline - a[0].baseline }
            assertThat(steps.max() - steps.min(), name = "$case: the rows' steps").isLessThanOrEqualTo(2)
            // The list ends where the cover's column does: the last small cover, a corner radius
            // below its artists' descenders, on the column's foot.
            val foot = layout.footerBottom * 2f
            val end = rows.last()[1].baseline + 15f * Fonts.DESCENT * 2f + ROW_COVER_CORNER * 2f
            assertThat(abs(end - foot), name = "$case: the list's end against the column's foot").isLessThanOrEqualTo(3f)
            // Nothing of the list reaches past the column's foot, or beyond its right edge.
            assertThat(ink.box(left, foot.toInt() + 1, 1920, 1080, threshold = 24).right, name = "$case: below the column's foot").isEqualTo(-1)
            val right = ((layout.columnLeft + layout.columnWidth) * 2).toInt()
            assertThat(ink.box(right + 2, 0, 1920, 1080, threshold = 24).right, name = "$case: right of the column").isEqualTo(-1)
        }
    }

    @Test
    fun theStagesSpacesGrowFromTheTitlesLinesToItsArtistToTheBar() {
        // Titles whose every line reaches down (p, g), at the largest size (two lines) and the
        // smallest (four): the spaces as the eye sees them, from the lowest descender of one line
        // to the highest ink of the next.
        val spiegel = "Spiegel im Spiegel"
        for (title in listOf("$spiegel im Spiegel", List(6) { spiegel }.joinToString(" im "))) {
            val track = io.github.hazymorning.klangbild.domain.TrackInfo(title, listOf("Arvo Pärt"), durationMs = 557_000)
            val model = Fixtures.model(track, album = false)
            val ink = render { Fixtures.Still { Stage(model) } }
            val lines = ink.lines(((model.scene.layout.columnLeft - 2) * 2).toInt(), 1920)
            // The title's lines are the only ones over 20 dp tall; then come the artist and the bar.
            val titleLines = lines.filter { it.inkBottom - it.inkTop > 40 }
            val after = lines.filter { it.inkTop > titleLines.last().inkBottom }
            val (artist, bar) = after
            val case = "${titleLines.size} lines"
            assertThat(titleLines.size, name = "$case: the title's lines").isGreaterThan(1)
            val within = titleLines.zipWithNext { a, b -> b.inkTop - a.inkBottom }.max()
            val below = artist.inkTop - titleLines.last().inkBottom
            assertThat(below.toFloat(), name = "$case: under the title against between its lines").isGreaterThan(2f * within)
            assertThat((bar.inkTop - artist.inkBottom).toFloat(), name = "$case: above the bar against under the title").isGreaterThan(1.5f * below)
        }
    }

    @Test
    fun theMenuHasAsMuchRoomOnEachSide() {
        val settings = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.wohnzimmer")
        val ink = render { Fixtures.Still { MenuScreen(Fixtures.menu(io.github.hazymorning.klangbild.config.MenuEntry.APPEARANCE, settings)) } }
        val all = ink.box(0, 0, 1920, 1080)
        assertThat(abs(all.left - (1919 - all.right)), name = "left of the heading, right of the values").isLessThanOrEqualTo(4)
    }

    @Test
    fun theSetupCodeAndItsTextStandInTheMiddle() {
        val info = SetupInfo(url = "http://192.168.1.23:8765", code = "482913", qr = QrCode.matrix("http://192.168.1.23:8765/?code=482913"))
        for (reason in SetupReason.entries) {
            val ink = render { Fixtures.Still { SetupScreen(reason, info, SetupArt.scene(info, tv)) } }
            val all = ink.box(0, 0, 1920, 1080)
            val card = ink.box(0, 0, 960, 1080)
            assertThat(abs(card.left - (1919 - all.right)), name = "$reason: left of the code, right of the longest line").isLessThanOrEqualTo(6)
            assertThat(abs(card.top - (1079 - card.bottom)), name = "$reason: above and below the code").isLessThanOrEqualTo(1)
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
