package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.hazymorning.klangbild.domain.ArtistBio
import io.github.hazymorning.klangbild.domain.BioPacing
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.rememberOffMain
import io.github.hazymorning.klangbild.ui.rememberTick
import io.github.hazymorning.klangbild.ui.text.LineBalancer
import io.github.hazymorning.klangbild.ui.text.lineBalancer
import io.github.hazymorning.klangbild.ui.theme.Fonts
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/*
 * A few sentences about the artist in the text column, while [StageModel.bio] is set. A page of
 * them stands where the text block does, centered on the cover from its first cap height to its
 * last baseline, on balanced lines in the artist's ink. The pages follow the song ([BioPacing]):
 * each stands as long as it takes to read at leisure, and the next comes once the one before has
 * faded, rising into place as new lines do. The status line keeps its place, title and artist
 * stand in the footer, and the progress bar comes back for a moment after a key, above them, as
 * beside the lyrics, and the pages always leave room for it. The pages are set once, off the main
 * thread; a frame only fades them.
 */

/** A page of a bio as set for the column: its lines, and how long it is for [BioPacing]. */
internal class BioPage(val layout: TextLayoutResult, val chars: Int)

/**
 * [bio] in pages for a column [width] px wide, each at most [rows] lines in [style] ([balancer]):
 * as many whole sentences as fit, the next page going on where one ends, and a paragraph always
 * starting a page. A sentence too long for a page goes on on the next after the last word that fits.
 */
internal fun planBio(bio: ArtistBio, style: TextStyle, width: Int, rows: Int, balancer: LineBalancer): List<BioPage> {
    val pages = ArrayList<BioPage>()
    for (sentences in bio.paragraphs) {
        val text = sentences.joinToString(" ")
        // Where each sentence ends in the paragraph.
        val ends = IntArray(sentences.size)
        var offset = 0
        for ((i, s) in sentences.withIndex()) {
            offset += s.length
            ends[i] = offset
            offset++
        }
        var start = 0
        while (start < text.length) {
            val rest = balancer.layout(AnnotatedString(text.substring(start)), style, width)
            if (rest.lineCount <= rows) {
                pages += BioPage(rest, text.length - start)
                break
            }
            // As far as the rows reach, back to a sentence's end, or else to a word's.
            fun before(limit: Int): Int = ends.lastOrNull { it in (start + 1)..limit } ?: text.lastIndexOf(' ', limit).takeIf { it > start } ?: limit
            var cut = before(start + rest.getLineEnd(rows - 1, visibleEnd = true))
            var page = balancer.layout(AnnotatedString(text.substring(start, cut)), style, width)
            // Balanced lines may need a row more than the whole paragraph's: a sentence or a word less.
            while (page.lineCount > rows && cut > start + 1) {
                cut = before(cut - 1)
                page = balancer.layout(AnnotatedString(text.substring(start, cut)), style, width)
            }
            pages += BioPage(page, cut - start)
            start = cut
            while (start < text.length && text[start] == ' ') start++
        }
    }
    return pages
}

/**
 * How many lines a page may have in [style]: no more than the text block may take ([BLOCK_SHARE]),
 * and with a line's room to spare to the status line and to the progress bar, which comes above
 * the footer for a moment after a key ([room]).
 */
internal fun bioRows(layout: StageGrid, style: TextStyle, room: WordsRoom): Int {
    val line = style.lineHeight.value
    val cap = style.fontSize.value * Fonts.CAP_HEIGHT
    val most = minOf(layout.columnArt * BLOCK_SHARE, 2f * (room.around(slot = 1f) - line))
    return (((most - cap) / line).toInt() + 1).coerceAtLeast(1)
}

/** The text column with a page about the artist in its middle ([TextLayer] while [StageModel.bio] is set). */
@Composable
internal fun BioText(
    model: StageModel,
    bio: ArtistBio,
    modifier: Modifier,
    alpha: (TextLine) -> Float,
    rise: (TextLine) -> Float,
    textLevel: () -> Float,
    shift: () -> Float,
) {
    val layout = model.scene.layout
    val styles = rememberStageStyles(model.scene.palette, model.scene.layout.textScale)
    val density = LocalDensity.current.density
    val resolver = LocalFontFamilyResolver.current
    val colW = (layout.columnWidth * density).roundToInt()
    val style = styles.bio
    val rows = remember(layout, styles) { bioRows(layout, style, WordsRoom(layout, styles)) }
    val pages = rememberOffMain(bio, colW, rows, style, density, resolver) {
        val measurer = TextMeasurer(resolver, Density(density), LayoutDirection.Ltr, cacheSize = 0)
        planBio(bio, style, colW, rows, lineBalancer(measurer))
    }

    // The page the song has come to, and the one before while it fades ([turn]: 0 to 1).
    val position by rememberUpdatedState(model.positionMs)
    var shown by remember(bio) { mutableIntStateOf(-1) }
    var before by remember(bio) { mutableIntStateOf(-1) }
    val turn = remember(bio) { Animatable(1f) }
    // The page follows the song on the stage's tick (in a still, once).
    val tick = rememberTick(fast = model.playing)
    if (pages != null) {
        LaunchedEffect(pages) {
            val chars = IntArray(pages.size) { pages[it].chars }
            snapshotFlow {
                tick.value
                BioPacing.pageAt(position(), chars)
            }.distinctUntilChanged().collect { page ->
                when {
                    page == shown -> Unit
                    // The first page stands at once: the column's own change brings it.
                    shown < 0 -> shown = page
                    else -> {
                        before = shown
                        shown = page
                        turn.snapTo(0f)
                        turn.animateTo(1f, tween(PAGE_TURN_MS, easing = LinearEasing))
                    }
                }
            }
        }
    }

    val words = pages?.let { p ->
        ColumnWords { x, center, a ->
            val left = x.toFloat()
            val t = turn.value
            val gone = Motion.phase(t, 0f, OUT_SHARE)
            val come = Motion.phase(t, IN_SHARE, 1f)
            p.getOrNull(before)?.takeIf { t < 1f }?.let { drawPage(it, left, center, a * (1f - gone), 0f, style) }
            p.getOrNull(shown)?.let { drawPage(it, left, center, a * come, Motion.RISE_DP * (1f - come), style) }
        }
    }
    WordsColumn(model, styles, modifier, alpha, rise, textLevel, shift, words)
}

/**
 * [page] from [x], centered on [center] (px) from its first cap height to its last baseline,
 * [riseDp] lower, at [alpha].
 */
private fun DrawScope.drawPage(page: BioPage, x: Float, center: Float, alpha: Float, riseDp: Float, style: TextStyle) {
    if (alpha <= 0.002f) return
    val l = page.layout
    val cap = style.fontSize.toPx() * Fonts.CAP_HEIGHT
    val first = l.getLineBaseline(0)
    val last = l.getLineBaseline(l.lineCount - 1)
    val top = (center - (first - cap + last) / 2f + riseDp * density).roundToInt().toFloat()
    drawText(l, topLeft = Offset(x, top), alpha = alpha)
}

/** A page turns over this long (ms): the one before fades out, then the next fades in. */
private const val PAGE_TURN_MS = 1_400

/** The page before is gone this share into a turn, and the next comes from this share on. */
private const val OUT_SHARE = 0.35f
private const val IN_SHARE = 0.45f
