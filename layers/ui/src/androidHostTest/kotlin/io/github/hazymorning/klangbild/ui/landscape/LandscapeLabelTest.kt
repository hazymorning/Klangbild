package io.github.hazymorning.klangbild.ui.landscape

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.landscape.LabelSide
import io.github.hazymorning.klangbild.landscape.PaperMotif
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.ui.composeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test

/**
 * What stands beside the mini-player's cover is one block, exactly in the middle of the cover,
 * measured on the rendered pixels (1080p, 2 px = 1 dp) and on what is seen: from the title's cap
 * height to the bar's lower edge, or, for a station without length and so without a bar, to the
 * last baseline (a title alone: its cap height to its baseline).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeLabelTest {
    @get:Rule
    val compose = composeRule()

    // Capitals with flat tops and feet: the ink's top is the cap height, its bottom the baseline.
    private val art = Catalog.SCHEME + SyntheticCover.BAUHAUS.name
    private val song = TrackInfo("HEIMATLIED", listOf("EMIL THEN"), durationMs = 200_000, artworkUrl = art)
    private val station = TrackInfo("HEIMATLIED", listOf("EMIL THEN"), artworkUrl = art)
    private val titleOnly = TrackInfo("HEIMATLIED", emptyList(), artworkUrl = art)

    @Test
    fun whatStandsBesideTheCoverIsCenteredOnIt() {
        for (side in listOf(LabelSide.LEFT, LabelSide.RIGHT)) {
            for ((what, track) in listOf("song with bar" to song, "station without bar" to station, "title alone" to titleOnly)) {
                val label = "$side, $what"
                val ink = render(track, side)
                val left = px(if (side == LabelSide.RIGHT) PaperMotif.LABEL_RIGHT_X else PaperMotif.LABEL_LEFT_X)
                val coverSide = px(PaperMotif.LABEL_HEIGHT)
                val cover = ink.blocks(left, left + coverSide).single()
                assertThat(cover.second - cover.first + 1, name = "$label: the cover's side").isEqualTo(coverSide)
                // Beside the cover: the lines, then (for a song) the bar.
                val beside = ink.blocks(left + coverSide + px(4f), 1920)
                val lines = ink.lines(left + coverSide + px(4f), 1920, 0, 1080)
                val withBar = track.durationMs != null
                val texts = if (withBar) lines.dropLast(1) else lines
                assertThat(beside, name = "$label: what stands beside the cover").hasSize(texts.size + if (withBar) 1 else 0)
                assertThat(texts, name = "$label: lines").hasSize(if (track.artists.isEmpty()) 1 else 2)
                val top = texts.first().capTop
                val bottom = if (withBar) beside.last().second else texts.last().baseline
                val middle = (top + bottom + 1) / 2f
                val coverMiddle = (cover.first + cover.second + 1) / 2f
                assertThat(abs(middle - coverMiddle), name = "$label: the block's middle (from $top to $bottom) on the cover's (${cover.first}–${cover.second})")
                    .isLessThanOrEqualTo(1f)
            }
        }
    }

    // --- rendering and measuring ----------------------------------------------------------------

    private fun px(dp: Float) = (dp * 2f).toInt()

    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)

    private fun render(track: TrackInfo, side: LabelSide): Ink {
        val scene = Fixtures.scene(track)
        val content: @Composable () -> Unit = {
            Fixtures.Still {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    LandscapeLabel(
                        LabelText(track, scene, playing = true, positionNow = { 100_000L }),
                        side,
                        inks = { INKS },
                    )
                }
            }
        }
        if (shown == null) compose.setContent { shown?.invoke() }
        shown = content
        compose.waitForIdle()
        return Ink(compose.onRoot().captureToImage().asAndroidBitmap())
    }

    /** A line of text: the top of most of its columns (the cap height) and where most end (the baseline). */
    private class Line(val capTop: Int, val baseline: Int)

    private class Ink(bmp: Bitmap) {
        private val w = bmp.width
        private val h = bmp.height
        private val lit = BooleanArray(w * h)

        init {
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            for (i in px.indices) {
                val p = px[i]
                lit[i] = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > THRESHOLD
            }
        }

        private fun rowLit(y: Int, x0: Int, x1: Int) = (x0 until x1).any { lit[y * w + it] }

        /** Runs of rows with ink between columns [x0] and [x1], top to bottom (first and last row). */
        fun blocks(x0: Int, x1: Int, y0: Int = 0, y1: Int = h): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var y = y0
            while (y < y1) {
                if (!rowLit(y, x0, x1)) {
                    y++
                    continue
                }
                val top = y
                while (y < y1 && rowLit(y, x0, x1)) y++
                out += top to y - 1
            }
            return out
        }

        fun lines(x0: Int, x1: Int, y0: Int, y1: Int): List<Line> = blocks(x0, x1, y0, y1).map { (top, bottom) ->
            val starts = IntArray(h)
            val ends = IntArray(h)
            for (x in x0 until x1) {
                var first = -1
                var last = -1
                for (y in top..bottom) if (lit[y * w + x]) {
                    if (first < 0) first = y
                    last = y
                }
                if (first >= 0) starts[first]++
                if (last >= 0) ends[last]++
            }
            Line(starts.indices.maxBy { starts[it] }, ends.indices.maxBy { ends[it] })
        }
    }

    private companion object {
        const val THRESHOLD = 90
        val INKS = intArrayOf(0xFFF0F0F0.toInt(), 0xFFD0D0D0.toInt(), 0xFFB0B0B0.toInt())
    }
}
