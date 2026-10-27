package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.hazymorning.klangbild.domain.LyricPlace
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.LyricsCursor
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.rememberTick
import io.github.hazymorning.klangbild.ui.text.lineBalancer
import io.github.hazymorning.klangbild.ui.theme.Faces
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * A track's lyrics in the text column. The line sung now stands in the middle, centered on the
 * cover as the text block is (from its first cap height to its last baseline), in the title's
 * ink. The lines before and after it are quiet and fade evenly with their distance from it. As
 * the track moves on, the lyrics glide one step and the highlight goes with them: the line that
 * comes is highlighted as it arrives, and the one before dims as it leaves. A break in the
 * singing only dims the line, and a jump fades the lyrics out, then back in at the new place. The
 * status line keeps its place, title and artist stand in the footer, and the progress bar comes
 * back for a moment after a key, above them. A track's lyrics are set once, in one size (the
 * largest its longest line allows), off the main thread. A frame only moves them and mixes two
 * inks.
 */

/**
 * The lyrics' sizes from large to small, set as titles are ([headingStyle]): a lyric line takes
 * at most two rows at the larger sizes, three at the smallest.
 */
internal val lyricsLadder = listOf(
    TitleStep(30f, 2, 420, 1.15f, -0.005f),
    TitleStep(26f, 2, 430, 1.16f, 0f),
    TitleStep(24f, 3, 430, 1.16f, 0f),
)

/** A track's lyrics as set for the column: each row on its own, and where each line stands (px). */
@Immutable
internal class LyricsPlan(
    val rows: List<LyricRow>,
    /** Each line's first cap height (px from the first line's cap height). */
    val tops: FloatArray,
    /** Each line's last baseline (px from the first line's cap height). */
    val bottoms: FloatArray,
    /** The rows' cap height (px). */
    val cap: Float,
    /** The gap from a line's last baseline to the next line's cap height (px). */
    val gap: Float,
    /** How far the rows are pulled left (px): large type has more room before its first stem. */
    val indent: Int,
) {
    /** Each line's middle, from its first cap height to its last baseline (px). */
    val centers = FloatArray(tops.size) { (tops[it] + bottoms[it]) / 2f }

    /** Where the lyrics stand with [place] in the middle (px). */
    fun center(place: LyricPlace): Float = centers[place.index.coerceIn(0, centers.size - 1)]

    /** The first row whose baseline is at least [y] (px). */
    fun firstAt(y: Float): Int {
        var lo = 0
        var hi = rows.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rows[mid].baseline < y) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** A row of a lyric line: its text laid out on one line, the line it belongs to, and its baseline (px). */
internal class LyricRow(val layout: TextLayoutResult, val line: Int, val baseline: Float)

/**
 * Sets [lyrics] for a column [columnWidth] dp wide with [room] dp above and below the middle: the
 * largest size of the ladder at which every voice takes no more rows than the size allows, with
 * balanced breaks, never inside a word, a short word kept with the next as in titles, and the
 * lines as far apart as the room allows ([lineGap]). A voice longer than [MAX_ROWS] rows even at
 * the smallest size ends after a whole word.
 */
internal fun planLyrics(lyrics: Lyrics, columnWidth: Float, room: Float, measurer: TextMeasurer, density: Float, faces: Faces): LyricsPlan {
    val colW = (columnWidth * density).roundToInt()
    val cjk = isMostlyCjk(lyrics.lines.take(CJK_SAMPLE).joinToString(" ") { it.text })
    val voices = lyrics.lines.map { line -> line.text.split('\n').map { glueShortWords(glueParentheses(AnnotatedString(it))) } }
    // Where a voice's balanced rows end, found once for each voice and style.
    val balancer = lineBalancer(measurer)
    val found = HashMap<Pair<AnnotatedString, TextStyle>, IntArray>()
    fun ends(text: AnnotatedString, style: TextStyle) = found.getOrPut(text to style) { balancer.lineEnds(text, style, colW) }
    // The longest voices decide, so they are tried first.
    val longest = voices.flatten().sortedByDescending { it.length }
    val step = lyricsLadder.firstOrNull { s ->
        val style = faces.headingStyle(s, cjk)
        longest.all { ends(it, style).size <= s.lines }
    } ?: lyricsLadder.last()
    val style = faces.headingStyle(step, cjk)
    val cap = style.fontSize.value * Fonts.CAP_HEIGHT * density
    val leading = style.lineHeight.value * density
    val gap = lineGap(style, step.lines, room) * density
    val rows = ArrayList<LyricRow>(lyrics.lines.size * 2)
    val tops = FloatArray(lyrics.lines.size)
    val bottoms = FloatArray(lyrics.lines.size)
    var baseline = cap
    voices.forEachIndexed { i, line ->
        tops[i] = baseline - cap
        var first = true
        for (voice in line) {
            val rowEnds = ends(voice, style)
            val text = voice.text
            for (k in 0 until minOf(rowEnds.size, MAX_ROWS)) {
                val start = if (k == 0) 0 else rowEnds[k - 1]
                val cut = k == MAX_ROWS - 1 && rowEnds.size > MAX_ROWS
                val end = if (cut) text.length else rowEnds[k]
                var row = text.substring(start, end).trim()
                if (cut) row = fitWords(measurer, row, style, colW)
                if (!first) baseline += leading
                first = false
                rows += LyricRow(measurer.measure(AnnotatedString(row), style, maxLines = 1, softWrap = false), i, baseline)
            }
        }
        bottoms[i] = baseline
        baseline += gap + cap
    }
    return LyricsPlan(rows, tops, bottoms, cap, gap, (opticalIndent(style.fontSize.value) * density).roundToInt())
}

/**
 * A track's lyrics on the stage, kept while its text shows or leaves: how they are set, and where
 * they stand. The theater keeps one per track ([LyricsStates]), so the lyrics that leave in a
 * change go from where they stood.
 */
@Stable
internal class LyricsState(val lyrics: Lyrics) {
    val cursor = LyricsCursor(lyrics)

    /** The lyrics as set, for the column they were set for ([planKey]). */
    private var plan by mutableStateOf<LyricsPlan?>(null)
    private var planKey: Any? = null

    /** Where the lyrics stand under the middle (px, in the plan's terms). */
    var scroll by mutableStateOf(Animatable(0f))
        private set

    /** The highlight of the line in the middle (0 to 1). */
    var highlight by mutableStateOf(Animatable(0f))
        private set

    /** Where the lyrics stood before a jump (px). They leave from there as [jump] runs to 1. */
    var from by mutableFloatStateOf(0f)
        private set

    /** How strong the lyrics that leave in a jump were when it began. */
    var fromAlpha by mutableFloatStateOf(1f)
        private set

    /** A jump's course, 1 at rest: the lyrics leave where they stood, then come at their new place. */
    val jump = Animatable(1f)

    /** The place the lyrics stand at or glide to. */
    private var shown: LyricPlace? = null

    /** The lyrics as set for the column [key], once they are. */
    fun planFor(key: Any): LyricsPlan? = plan?.takeIf { planKey == key }

    /**
     * Keeps [p] as the lyrics set for the column [key], standing at [place]. Lyrics that another
     * column set first for it stay, and so does the place they stand at. The animations start
     * anew, since a still sets its plan while composing, where nothing can snap them.
     */
    fun keep(key: Any, p: LyricsPlan, place: LyricPlace): LyricsPlan {
        if (planKey == key) plan?.let { return it }
        planKey = key
        plan = p
        shown = place
        scroll = Animatable(p.center(place))
        highlight = Animatable(if (place.lit) 1f else 0f)
        return p
    }

    /**
     * Moves the lyrics to [place]: a glide by one line (one that comes while the last still runs
     * carries on from its pace), and for more a jump: the lyrics go, then come at the new place.
     */
    suspend fun moveTo(place: LyricPlace, plan: LyricsPlan, scope: CoroutineScope) {
        val last = shown ?: return
        shown = place
        val target = plan.center(place)
        val s: Animatable<Float, AnimationVector1D> = scroll
        if (abs(place.index - last.index) <= 1) {
            if (s.targetValue != target) {
                val spec = if (s.isRunning) spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow) else tween<Float>(LYRICS_GLIDE_MS, easing = Motion.Dimmer)
                scope.launch { s.animateTo(target, spec) }
            }
        } else {
            val j = jump.value
            if (jump.isRunning && j < JUMP_IN) {
                // The lyrics that stood are still going: the new place only changes what comes.
                s.snapTo(target)
            } else {
                // The lyrics that came (or are coming) go from as strong as they are. The jump starts
                // before they move, so no frame shows the new place at full strength.
                fromAlpha = if (jump.isRunning) Motion.phase(j, JUMP_IN, 1f, Motion.Dimmer) else 1f
                from = s.value
                jump.snapTo(0f)
                s.snapTo(target)
                scope.launch { jump.animateTo(1f, tween(JUMP_MS, easing = LinearEasing)) }
            }
        }
        val h = highlight
        if (last.lit != place.lit) scope.launch { h.animateTo(if (place.lit) 1f else 0f, tween(HIGHLIGHT_MS, easing = Motion.Dimmer)) }
    }
}

/** The lyrics of a theater's tracks: those shown and those leaving keep their places. */
internal class LyricsStates {
    private val states = HashMap<Lyrics, LyricsState>()

    fun of(lyrics: Lyrics): LyricsState = states.getOrPut(lyrics) { LyricsState(lyrics) }

    /** Drops the state of all lyrics except [kept]. */
    fun keep(vararg kept: Lyrics?) {
        states.keys.retainAll(kept.filterNotNull().toSet())
    }
}

/** The theater's lyrics states. Without one (a still), each column keeps its own. */
internal val LocalLyricsStates = staticCompositionLocalOf<LyricsStates?> { null }

/**
 * The text column with the lyrics ([TextLayer] while [StageModel.lyrics] is set): the lyrics in
 * the middle, the track's title and artist in the footer, and the slot above them while it shows.
 * [alpha] and [rise] animate a change as for the text block: the lyrics as the title, the slot
 * and the footer as themselves.
 */
@Composable
internal fun LyricsText(
    model: StageModel,
    lyrics: Lyrics,
    modifier: Modifier,
    alpha: (TextLine) -> Float,
    rise: (TextLine) -> Float,
    textLevel: () -> Float,
    shift: () -> Float,
) {
    val layout = model.scene.layout
    val styles = rememberStageStyles(model.scene.palette)
    val room = remember(layout, styles) { LyricsRoom(layout, styles) }
    val inks = remember(styles) { Array(INK_STEPS + 1) { lerp(styles.quiet, styles.ink, it / INK_STEPS.toFloat()) } }
    val states = LocalLyricsStates.current
    val state = states?.of(lyrics) ?: remember(lyrics) { LyricsState(lyrics) }
    val position by rememberUpdatedState(model.positionMs)
    // The lyrics first stand where the track is.
    val plan = rememberLyricsPlan(state, layout, room.around()) { state.cursor.at(position()) }
    // The place follows the position on the stage's tick (in a still, once).
    val tick = rememberTick(fast = model.playing)
    if (plan != null) {
        LaunchedEffect(state, plan) {
            snapshotFlow {
                tick.value
                state.cursor.at(position())
            }.distinctUntilChanged().collect { place -> state.moveTo(place, plan, this) }
        }
    }

    val slotText = model.slotText
    // While the slot shows above the footer, the lyrics make room for it.
    val slotShown = animateFloatAsState(if (slotOf(model) != null) 1f else 0f, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer), label = "lyrics-slot-shown")
    val colW = (layout.columnWidth * LocalDensity.current.density).roundToInt()

    fun line(which: TextLine): Modifier = Modifier.lineMotion(which, alpha, rise)

    Layout(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = textLevel().coerceIn(0f, 1f) },
        content = {
            // 0: the lyrics
            Canvas(Modifier.fillMaxSize()) {
                val p = plan ?: return@Canvas
                val a = alpha(TextLine.TITLE).coerceIn(0f, 1f)
                if (a <= 0.002f) return@Canvas
                val x = (((layout.columnLeft + shift()) * density).roundToInt() + p.indent).toFloat()
                val focus = layout.textCenter * density + (rise(TextLine.TITLE) * density).roundToInt()
                val around = room.around(slotShown.value)
                val highlight = state.highlight.value
                // A jump: the lyrics that stood go first, then the new ones come, never both at once.
                val j = state.jump.value
                val gone = Motion.phase(j, 0f, JUMP_OUT, Motion.Dimmer)
                val come = Motion.phase(j, JUMP_IN, 1f, Motion.Dimmer)
                if (j < 1f) drawLyrics(p, state.from, x, focus, around, inks, highlight, a * state.fromAlpha * (1f - gone))
                drawLyrics(p, state.scroll.value, x, focus, around, inks, highlight, a * come)
            }
            // 1: the slot above the footer, whose changes crossfade
            SlotContent(model, styles, line(TextLine.SLOT), label = "lyrics-slot")
            // 2: the footer, which names the track itself
            val track = model.footer?.let { rememberFooter(it, model.footerTitle, styles, colW) }
            if (track != null) BasicText(AnnotatedString(isolateRightToLeft(track)), line(TextLine.FOOTER), style = styles.footer, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
        },
    ) { measurables, constraints ->
        val lyricLines = measurables[0].measure(constraints)
        val slot = measurables[1].measure(Constraints(minWidth = colW, maxWidth = colW))
        val footer = measurables[2].measure(Constraints(maxWidth = colW))
        // The footer's descenders end where the column ends, and the slot's baseline stands above it.
        val footerBaseline = (layout.footerBottom * density - styles.footer.fontSize.toPx() * Fonts.DESCENT).roundToInt()
        val slotBaseline = footerBaseline - (styles.footer.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt() - (SLOT_GAP * density).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            lyricLines.place(0, 0)
            // Read while placing: a column that glides is only placed anew.
            val x = ((layout.columnLeft + shift()) * density).roundToInt()
            if (slot.height > 0) slot.place(x, slotBaseline - if (slotText != null) slot.firstBaselineOrHeight() else slot.height)
            if (footer.height > 0) footer.place(x, footerBaseline - footer.firstBaselineOrHeight())
        }
    }
}

/**
 * The lyrics as set for [state]'s column with [room] around the middle ([planLyrics]), standing
 * at [place] once they are: at once in a still, else off the main thread (nothing until then).
 */
@Composable
private fun rememberLyricsPlan(state: LyricsState, layout: StageGrid, room: Float, place: () -> LyricPlace): LyricsPlan? {
    val density = LocalDensity.current.density
    val resolver = LocalFontFamilyResolver.current
    val faces = faces()
    val key = listOf(layout.columnWidth, room, density, faces)
    fun plan() = planLyrics(state.lyrics, layout.columnWidth, room, TextMeasurer(resolver, Density(density), LayoutDirection.Ltr, cacheSize = 0), density, faces)
    if (LocalStill.current) return remember(state, key) { state.keep(key, plan(), place()) }
    val ready = state.planFor(key)
    if (ready == null) {
        LaunchedEffect(state, key) {
            val p = withContext(Dispatchers.Default) { plan() }
            state.keep(key, p, place())
        }
    }
    return ready
}

/** The lyrics' room around the middle (dp). */
internal class LyricsRoom(layout: StageGrid, styles: StageStyles) {
    private val status = layout.textCenter - (layout.statusTop + styles.label.fontSize.value * Fonts.CAP_HEIGHT)
    private val footer = layout.footerBottom - styles.footer.fontSize.value * (Fonts.DESCENT + Fonts.CAP_HEIGHT) - layout.textCenter
    // The bar's block from its top to its labels' baseline.
    private val bar = footer - SLOT_GAP - (BAR_THICKNESS + BAR_LABEL_GAP + styles.time.fontSize.value * Fonts.CAP_HEIGHT)

    /**
     * As much above the middle as below, so the lyrics fade alike up and down: to the status
     * line's baseline or to the footer's cap height, whichever is nearer, and to the bar's top
     * while it shows ([slot] 0 to 1).
     */
    fun around(slot: Float = 0f): Float = minOf(status, footer + (bar - footer) * slot)
}

/**
 * The rows of [plan] with the lyrics standing at [scroll] (px) under the middle ([focus], px),
 * from [x]: each line fading as a whole with its distance from the middle ([lineAlpha]) within
 * [around] (dp) above and below it, the line in the middle highlighted by [highlight] (its ink
 * taken from [inks], the quiet ink to the title's), [alpha] for all of them.
 */
private fun DrawScope.drawLyrics(
    plan: LyricsPlan,
    scroll: Float,
    x: Float,
    focus: Float,
    around: Float,
    inks: Array<Color>,
    highlight: Float,
    alpha: Float,
) {
    if (alpha <= 0.002f) return
    val d = density
    val rows = plan.rows
    var i = plan.firstAt(scroll - around * d)
    val end = scroll + around * d + plan.cap
    var line = -1
    var f = 0f
    var color = inks[0]
    while (i < rows.size) {
        val row = rows[i++]
        if (row.baseline > end) break
        if (row.line != line) {
            line = row.line
            f = lineAlpha((plan.tops[line] - scroll) / d, (plan.bottoms[line] - scroll) / d, around, plan.gap / d)
            color = inks[(highlightOf(plan, line, scroll) * highlight * INK_STEPS).roundToInt().coerceIn(0, INK_STEPS)]
        }
        if (f <= 0.003f) continue
        val top = (focus + row.baseline - scroll - row.layout.firstBaseline).roundToInt().toFloat()
        drawText(row.layout, color = color, topLeft = Offset(x, top), alpha = alpha * f)
    }
}

/**
 * How present a line is that reaches from [top] to [bottom] (dp from the middle, its first cap
 * height and last baseline), with [around] of room above and below: whole in the middle, fading
 * evenly with the distance of its nearer edge, so that the lines next to the one sung are already
 * quieter, and gone before its farther edge comes nearer the status line, the footer or the bar
 * than [gap], the gap between lines. A line fading away may fall below the inks' contrast, but the
 * one sung never does.
 */
private fun lineAlpha(top: Float, bottom: Float, around: Float, gap: Float): Float {
    if (top < 0f && bottom > 0f) return 1f
    val near = if (bottom <= 0f) -bottom else top
    val far = if (bottom <= 0f) -top else bottom
    val limit = around - gap
    val byDistance = (1f - near / limit.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val byEdge = 1f - smoothstep(limit - gap * EDGE_FADE, limit, far)
    return byDistance * byEdge
}

/**
 * How far [line] is highlighted with the lyrics at [scroll]: fully in the middle, not at all one
 * line away, so the highlight goes along as the lyrics glide.
 */
private fun highlightOf(plan: LyricsPlan, line: Int, scroll: Float): Float {
    val c = plan.centers
    val off = c[line] - scroll
    val neighbor = if (off > 0f) c.getOrNull(line - 1) else c.getOrNull(line + 1)
    // A line without a neighbor on that side takes three cap heights as the step to one.
    val step = neighbor?.let { abs(c[line] - it) } ?: (plan.cap * 3f)
    return (1f - abs(off) / step).coerceIn(0f, 1f)
}

/** The track in the footer, which has no lead here ([fitFooter]). */
@Composable
private fun rememberFooter(full: String, title: String?, styles: StageStyles, width: Int): String {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    return remember(full, title, styles, width) { fitFooter(measurer, full, title, styles.footer, width) { AnnotatedString(isolateRightToLeft(it)) } }
}

/**
 * The gap from a lyric line's last baseline to the next line's cap height (dp) for lyrics in
 * [style] on at most [rows] rows a line, with [room] above and below the middle: as much as lets
 * lines of the most rows, the one sung and one on either side, keep [OUTER_GAP_SHARE] times that
 * gap to the status line and the footer, from [LINE_GAP_MIN] to [LINE_GAP_MAX] em.
 */
internal fun lineGap(style: TextStyle, rows: Int, room: Float): Float {
    val size = style.fontSize.value
    val most = size * Fonts.CAP_HEIGHT + (rows - 1) * style.lineHeight.value
    return ((room - most / 2f - most) / (1f + OUTER_GAP_SHARE)).coerceIn(size * LINE_GAP_MIN, size * LINE_GAP_MAX)
}

/** The smallest gap between lyric lines (em): clearly more than between a line's rows. */
private const val LINE_GAP_MIN = 0.8f

/** The largest gap between lyric lines (em), where the room allows. */
private const val LINE_GAP_MAX = 1.2f

/** The gap to the status line and the footer, as a share of the gap between lines. */
private const val OUTER_GAP_SHARE = 1.6f

/** A line leaving at an edge fades over this share of the gap between lines. */
private const val EDGE_FADE = 0.5f

/** From the slot's baseline to the footer's cap height under it (dp). */
private const val SLOT_GAP = 26f

/** Each voice of a lyric line takes at most this many rows, the last one ending after a whole word. */
private const val MAX_ROWS = 4

/** Enough lyric lines to tell their script. */
private const val CJK_SAMPLE = 12

/** Steps between the quiet ink and the title's: more than the eye tells apart in a glide. */
private const val INK_STEPS = 48

/** How long the lyrics take to glide by one line (ms). */
private const val LYRICS_GLIDE_MS = 500

/** How long the highlight takes to fade out at a break, or back in after it (ms). */
private const val HIGHLIGHT_MS = 600

/** How long a jump takes (ms). */
private const val JUMP_MS = 600

/** The lyrics that stood are gone this share into a jump. */
private const val JUMP_OUT = 0.4f

/** The new lyrics come from this share of a jump on. */
private const val JUMP_IN = 0.45f
