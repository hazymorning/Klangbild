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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.hazymorning.klangbild.domain.LyricPlace
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.LyricsCursor
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.rememberFrames
import io.github.hazymorning.klangbild.ui.theme.Faces
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * A song's words in the text column. The line sung now stands in the middle, centered on the
 * artwork as the text block is (from its first cap height to its last baseline), in the title's
 * ink. The lines before and after it are quiet and fade with distance, as the settings list does.
 * As the song moves on, the list glides one step, and the light goes with it: the line that comes
 * is lit as it arrives, the one before dims as it leaves. A break in the singing only dims the
 * line; a jump cross-fades the list to its new place. The status line keeps its place; title and
 * artist stand where "Up next" stands; the progress bar comes back for a moment after a key,
 * above them. A song's words are set once, in one size (the largest its longest line allows),
 * off the main thread; a frame only moves the list and mixes two inks.
 */

/** One size for a song's words: [size] sp, a line on at most [rows] rows, [leading] between rows. */
private class LyricsStep(val size: Float, val rows: Int, val weight: Int, val leading: Float, val tracking: Float)

/** From large to small, as the title's ladder: two rows a line at the larger sizes, three at the smallest. */
private val lyricsLadder = listOf(
    LyricsStep(30f, 2, 420, 1.15f, -0.005f),
    LyricsStep(26f, 2, 430, 1.16f, 0f),
    LyricsStep(24f, 3, 430, 1.16f, 0f),
)

/** The words' style at [step]; Japanese, Chinese and Korean words a little smaller, as titles are. */
private fun lyricsStyle(faces: Faces, step: LyricsStep, cjk: Boolean): TextStyle {
    val size = if (cjk) step.size * StageStyles.CJK_SCALE else step.size
    return faces.titleType(size = size, weight = step.weight, lineHeight = size * step.leading + if (cjk) 6f else 0f, tracking = if (cjk) 0f else step.tracking)
        .merge(TextStyle(lineBreak = LineBreak.Heading, textDirection = TextDirection.Content, textAlign = TextAlign.Left))
}

/** The words of one song as set for the column: each row on its own line, and where each line stands (px). */
@Immutable
internal class LyricsPlan(
    val rows: List<LyricRow>,
    /** Each line's first cap height and last baseline (px from the first line's cap height). */
    val tops: FloatArray,
    val bottoms: FloatArray,
    /** The rows' cap height (px). */
    val cap: Float,
    /** How far the rows are pulled left (px): large type has more room before its first stem. */
    val indent: Int,
) {
    /** Each line's middle, from its first cap height to its last baseline (px). */
    val centers = FloatArray(tops.size) { (tops[it] + bottoms[it]) / 2f }

    /** Where the list stands with [place] in the middle (px). */
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

/** A row of a line: its words laid out on one line, the line it belongs to, and its baseline (px). */
internal class LyricRow(val layout: TextLayoutResult, val line: Int, val baseline: Float)

/**
 * Sets [lyrics] for a column [columnWidth] dp wide: the largest size of the ladder at which every
 * line takes no more rows than the size allows (two voices each count on their own), with
 * balanced breaks, never inside a word, a short word kept with the next as in titles. A line
 * longer than [MAX_ROWS] rows even at the smallest size ends after a whole word.
 */
internal fun planLyrics(lyrics: Lyrics, columnWidth: Float, measurer: TextMeasurer, density: Float, faces: Faces): LyricsPlan {
    val colW = (columnWidth * density).roundToInt()
    val cjk = isMostlyCjk(lyrics.lines.take(CJK_SAMPLE).joinToString(" ") { it.text })
    val parts = lyrics.lines.map { line -> line.text.split('\n').map { glueShortWords(glueParentheses(AnnotatedString(it))) } }
    fun wrapped(text: AnnotatedString, style: TextStyle) =
        measurer.measure(text, style, constraints = Constraints(maxWidth = colW), maxLines = MAX_ROWS + 1)
    // The longest parts decide, so they are tried first.
    val longest = parts.flatten().sortedByDescending { it.length }
    val step = lyricsLadder.firstOrNull { s ->
        val style = lyricsStyle(faces, s, cjk)
        longest.all { wrapped(it, style).lineCount <= s.rows }
    } ?: lyricsLadder.last()
    val style = lyricsStyle(faces, step, cjk)
    val cap = style.fontSize.value * Fonts.CAP_HEIGHT * density
    val leading = style.lineHeight.value * density
    val gap = style.fontSize.value * LINE_GAP_EM * density
    val rows = ArrayList<LyricRow>(lyrics.lines.size * 2)
    val tops = FloatArray(lyrics.lines.size)
    val bottoms = FloatArray(lyrics.lines.size)
    var baseline = cap
    parts.forEachIndexed { i, line ->
        tops[i] = baseline - cap
        var first = true
        for (part in line) {
            val r = wrapped(part, style)
            val text = part.text
            for (k in 0 until minOf(r.lineCount, MAX_ROWS)) {
                val start = r.getLineStart(k)
                val cut = k == MAX_ROWS - 1 && r.lineCount > MAX_ROWS
                val end = if (cut) text.length else r.getLineEnd(k, visibleEnd = true)
                var words = text.substring(start, end.coerceAtLeast(start)).trim()
                if (cut) words = fitWords(measurer, words, style, colW)
                if (!first) baseline += leading
                first = false
                rows += LyricRow(measurer.measure(AnnotatedString(words), style, maxLines = 1, softWrap = false), i, baseline)
            }
        }
        bottoms[i] = baseline
        baseline += gap + cap
    }
    return LyricsPlan(rows, tops, bottoms, cap, (opticalIndent(style.fontSize.value) * density).roundToInt())
}

/**
 * The words of one song on the stage, kept while its text shows or leaves: how they are set, and
 * where the list stands. The theater keeps one per song ([LyricsViews]), so the words that leave
 * at a change go from where they stood.
 */
@Stable
internal class LyricsView(val lyrics: Lyrics) {
    val cursor = LyricsCursor(lyrics)

    /** The words as set, for the column they were set for ([planKey]). */
    private var plan by mutableStateOf<LyricsPlan?>(null)
    private var planKey: Any? = null

    /** Where the list stands under the middle (px, in the plan's terms), and how lit the line there is. */
    var scroll by mutableStateOf(Animatable(0f))
        private set
    var lit by mutableStateOf(Animatable(0f))
        private set

    /**
     * A jump: the list leaves where it stood ([from], px, as strong as [fromAlpha]), then comes at
     * its new place, as [jump] runs to 1.
     */
    var from by mutableFloatStateOf(0f)
        private set
    var fromAlpha by mutableFloatStateOf(1f)
        private set
    val jump = Animatable(1f)

    /** The place the list stands at or glides to. */
    private var shown: LyricPlace? = null

    /** The words as set for the column [key], once they are. */
    fun planFor(key: Any): LyricsPlan? = plan?.takeIf { planKey == key }

    /**
     * Keeps [p] as the words set for the column [key], the list standing at [place]. Words that
     * another column set first for it stay, and so does the place their list stands at.
     */
    fun keep(key: Any, p: LyricsPlan, place: LyricPlace): LyricsPlan {
        if (planKey == key) plan?.let { return it }
        planKey = key
        plan = p
        shown = place
        scroll = Animatable(p.center(place))
        lit = Animatable(if (place.lit) 1f else 0f)
        return p
    }

    /**
     * Moves the list to [place]: a glide by one line (one that comes while the last still runs
     * carries on from its pace), and for more a jump: the words go, then come at the new place.
     */
    suspend fun go(place: LyricPlace, plan: LyricsPlan, scope: CoroutineScope) {
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
                // The words that stood are still going: the new place only changes what comes.
                s.snapTo(target)
            } else {
                // The words that came (or are coming) go from as strong as they are.
                fromAlpha = if (jump.isRunning) Motion.phase(j, JUMP_IN, 1f, Motion.Dimmer) else 1f
                from = s.value
                s.snapTo(target)
                scope.launch {
                    jump.snapTo(0f)
                    jump.animateTo(1f, tween(JUMP_MS, easing = LinearEasing))
                }
            }
        }
        val l = lit
        if (last.lit != place.lit) scope.launch { l.animateTo(if (place.lit) 1f else 0f, tween(LIT_MS, easing = Motion.Dimmer)) }
    }
}

/** A theater's songs' words on the stage: the one shown and the one leaving keep their places. */
internal class LyricsViews {
    private val views = HashMap<Lyrics, LyricsView>()

    fun of(lyrics: Lyrics): LyricsView = views.getOrPut(lyrics) { LyricsView(lyrics) }

    /** Lets go of every song's words but [kept]. */
    fun keep(vararg kept: Lyrics?) {
        views.keys.retainAll(kept.filterNotNull().toSet())
    }
}

/** The theater's songs' words; without one (a still), each column keeps its own. */
internal val LocalLyricsViews = staticCompositionLocalOf<LyricsViews?> { null }

/**
 * The text column with the song's words ([TextLayer] while [StageModel.lyrics] is set): the words
 * in the middle, the song's title and artist at the bottom, and the progress bar (or a notice)
 * above them while it shows. [alpha] and [rise] animate a change as for the text block: the words
 * as the title, the bar as the bottom line, the song as what comes next.
 */
@Composable
internal fun LyricsText(
    model: StageModel,
    lyrics: Lyrics,
    modifier: Modifier,
    alpha: (TextLine) -> Float,
    rise: (TextLine) -> Float,
    dim: () -> Float,
    shift: () -> Float,
) {
    val layout = model.scene.layout
    val faces = faces()
    val styles = remember(model.scene.palette, faces) { StageStyles(model.scene.palette, faces) }
    val room = remember(layout, styles) { LyricsRoom(layout, styles) }
    val inks = remember(styles) { Array(INK_STEPS + 1) { lerp(styles.quiet, styles.ink, it / INK_STEPS.toFloat()) } }
    val views = LocalLyricsViews.current
    val view = views?.of(lyrics) ?: remember(lyrics) { LyricsView(lyrics) }
    val position by rememberUpdatedState(model.positionMs)
    // The list first stands where the song is.
    val plan = rememberLyricsPlan(view, layout) { view.cursor.at(position()) }
    val playing = model.status == PlayerStatus.PLAYING || model.status == PlayerStatus.BUFFERING

    // The place follows the position on the stage's tick (in a still, once).
    val frames = rememberFrames(fast = playing)
    if (plan != null) {
        LaunchedEffect(view, plan) {
            snapshotFlow {
                frames.value
                view.cursor.at(position())
            }.distinctUntilChanged().collect { place -> view.go(place, plan, this) }
        }
    }

    val bottomLine = model.line
    val slot = bottomOf(model)
    // While the bar or a notice shows above the song, the words make room for it.
    val slotShown = animateFloatAsState(if (slot != null) 1f else 0f, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer), label = "lyrics-slot")
    val colW = (layout.columnWidth * LocalDensity.current.density).roundToInt()

    fun line(which: TextLine): Modifier = Modifier.lineMotion(which, alpha, rise)

    Layout(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = dim().coerceIn(0f, 1f) },
        content = {
            // 0: the words
            Canvas(Modifier.fillMaxSize()) {
                val p = plan ?: return@Canvas
                val a = alpha(TextLine.TITLE).coerceIn(0f, 1f)
                if (a <= 0.002f) return@Canvas
                val x = (((layout.columnLeft + shift()) * density).roundToInt() + p.indent).toFloat()
                val focus = layout.textCenter * density + (rise(TextLine.TITLE) * density).roundToInt()
                val below = room.below(slotShown.value)
                val glow = view.lit.value
                // A jump: the words that stood go first, then the new ones come (never both at once).
                val j = view.jump.value
                val gone = Motion.phase(j, 0f, JUMP_OUT, Motion.Dimmer)
                val come = Motion.phase(j, JUMP_IN, 1f, Motion.Dimmer)
                if (j < 1f) drawLyrics(p, view.from, x, focus, room.above, below, inks, glow, a * view.fromAlpha * (1f - gone))
                drawLyrics(p, view.scroll.value, x, focus, room.above, below, inks, glow, a * come)
            }
            // 1: the progress bar or a notice, above the song; changes cross-fade
            BottomLine(slot, model, styles, playing, line(TextLine.BOTTOM), label = "lyrics-slot-line")
            // 2: the song itself, where "Up next" stands
            val song = model.next?.let { fitSong(it, model.nextTitle, styles, colW) }
            if (song != null) BasicText(songText(song), line(TextLine.NEXT), style = styles.next, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
        },
    ) { measurables, constraints ->
        val words = measurables[0].measure(constraints)
        val bottom = measurables[1].measure(Constraints(minWidth = colW, maxWidth = colW))
        val song = measurables[2].measure(Constraints(maxWidth = colW))
        // The song's descenders end where the column ends; the bar's labels (or a notice) stand above it.
        val footer = (layout.footerBottom * density - styles.next.fontSize.toPx() * Fonts.DESCENT).roundToInt()
        val slotBaseline = footer - (styles.next.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt() - (Gaps.BOTTOM * density).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            words.place(0, 0)
            // Read while placing: a column that glides is only placed anew.
            val x = ((layout.columnLeft + shift()) * density).roundToInt()
            if (bottom.height > 0) {
                val baseline = if (bottomLine != null) bottom[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: bottom.height else bottom.height
                bottom.place(x, slotBaseline - baseline)
            }
            if (song.height > 0) {
                val b = song[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: song.height
                song.place(x, footer - b)
            }
        }
    }
}

/**
 * The words as set for [view]'s column, the list standing at [place] once they are: at once in a
 * still, else off the main thread (nothing until then).
 */
@Composable
private fun rememberLyricsPlan(view: LyricsView, layout: StageGrid, place: () -> LyricPlace): LyricsPlan? {
    val density = LocalDensity.current.density
    val resolver = LocalFontFamilyResolver.current
    val faces = faces()
    val key = layout.columnWidth to density
    fun plan() = planLyrics(view.lyrics, layout.columnWidth, TextMeasurer(resolver, Density(density), LayoutDirection.Ltr, cacheSize = 0), density, faces)
    if (LocalStill.current) return remember(view, key) { view.keep(key, plan(), place()) }
    val ready = view.planFor(key)
    if (ready == null) {
        LaunchedEffect(view, key) {
            val p = withContext(Dispatchers.Default) { plan() }
            view.keep(key, p, place())
        }
    }
    return ready
}

/**
 * The room of the words above and below the middle (dp): to the status line's baseline, and to
 * the song's cap height, or to the bar's top while it shows.
 */
private class LyricsRoom(layout: StageGrid, styles: StageStyles) {
    val above: Float = layout.textCenter - (layout.statusTop + styles.label.fontSize.value * Fonts.CAP_HEIGHT)
    private val song = layout.footerBottom - styles.next.fontSize.value * (Fonts.DESCENT + Fonts.CAP_HEIGHT) - layout.textCenter
    private val bar = song - Gaps.BOTTOM - BAR_BLOCK

    /** Below the middle, as far as the bar lets them while it shows ([slot] 0 to 1). */
    fun below(slot: Float): Float = song + (bar - song) * slot
}

/**
 * The rows of [plan] with the list standing at [scroll] (px) under the middle ([focus], px), from
 * [x]: each line fading as a whole with its distance from the middle ([lineAlpha]) within
 * [above] and [below] (dp), the line in the middle lit by [glow] (its ink taken from [inks], the
 * quiet ink to the title's), [alpha] for all of them.
 */
private fun DrawScope.drawLyrics(
    plan: LyricsPlan,
    scroll: Float,
    x: Float,
    focus: Float,
    above: Float,
    below: Float,
    inks: Array<Color>,
    glow: Float,
    alpha: Float,
) {
    if (alpha <= 0.002f) return
    val d = density
    val rows = plan.rows
    var i = plan.firstAt(scroll - above * d)
    val end = scroll + below * d + plan.cap
    var line = -1
    var f = 0f
    var color = inks[0]
    while (i < rows.size) {
        val row = rows[i++]
        if (row.baseline > end) break
        if (row.line != line) {
            line = row.line
            f = lineAlpha((plan.tops[line] - scroll) / d, (plan.bottoms[line] - scroll) / d, above, below)
            color = inks[(glowOf(plan, line, scroll) * glow * INK_STEPS).roundToInt().coerceIn(0, INK_STEPS)]
        }
        if (f <= 0.003f) continue
        val top = (focus + row.baseline - scroll - row.layout.firstBaseline).roundToInt().toFloat()
        drawText(row.layout, color = color, topLeft = Offset(x, top), alpha = alpha * f)
    }
}

/**
 * How present a line is that reaches from [top] to [bottom] (dp from the middle, its first cap
 * height and last baseline), with [above] and [below] of room: whole in the middle and near it,
 * fading with the distance of its nearer edge as the settings list fades, and gone before its
 * farther edge comes within [EDGE_GONE] of the status line, the song or the bar.
 */
private fun lineAlpha(top: Float, bottom: Float, above: Float, below: Float): Float {
    if (top < 0f && bottom > 0f) return 1f
    val near = if (bottom <= 0f) -bottom else top
    val far = if (bottom <= 0f) -top else bottom
    val limit = (if (bottom <= 0f) above else below) - EDGE_GONE
    val byDistance = 1f - smoothstep(FADE_FROM, limit.coerceAtLeast(FADE_FROM + 1f), near)
    val byEdge = 1f - smoothstep(limit - EDGE_FADE, limit, far)
    return byDistance * byEdge
}

/**
 * How lit [line] is with the list at [scroll]: fully in the middle, not at all one line away,
 * so the light goes along as the list glides.
 */
private fun glowOf(plan: LyricsPlan, line: Int, scroll: Float): Float {
    val c = plan.centers
    val off = c[line] - scroll
    val neighbor = if (off > 0f) c.getOrNull(line - 1) else c.getOrNull(line + 1)
    val step = neighbor?.let { abs(c[line] - it) } ?: (plan.cap * 3f)
    return (1f - abs(off) / step).coerceIn(0f, 1f)
}

/** The song as "Up next" names a song: title and artist, or the title alone rather than a cut-off artist. */
@Composable
private fun fitSong(full: String, title: String?, styles: StageStyles, width: Int): String {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    return remember(full, title, styles, width) {
        if (title == null || measurer.measure(songText(full), styles.next, maxLines = 1, softWrap = false).size.width <= width) full else title
    }
}

/** The song's line, isolated if right-to-left (as "Up next" sets what comes). */
private fun songText(text: String): AnnotatedString = AnnotatedString(if (isRightToLeft(text)) "\u2067$text\u2069" else text)

/** The space from a line's last baseline to the next line's cap height (em of the words). */
private const val LINE_GAP_EM = 0.8f

/** A line whose nearer edge is within this distance of the middle is whole (dp): the lines next to the one sung. */
private const val FADE_FROM = 40f

/** A line is gone before its farther edge comes this close to the status line, the song or the bar (dp)... */
private const val EDGE_GONE = 8f

/** ...and it fades over this stretch before (dp). */
private const val EDGE_FADE = 22f

/** The progress bar's block from its top to its labels' baseline (dp): the bar, the gap, the labels' cap height. */
private const val BAR_BLOCK = BAR_THICKNESS + BAR_LABEL_GAP + 15f * Fonts.CAP_HEIGHT

/** A line takes at most this many rows, however long. */
private const val MAX_ROWS = 4

/** Enough of the words to tell their script. */
private const val CJK_SAMPLE = 12

/** Steps between the quiet ink and the title's: more than the eye tells apart in a glide. */
private const val INK_STEPS = 48

/** The words glide one step in this long (ms). */
private const val LYRICS_GLIDE_MS = 500

/** A line dims at a break, and lights after it, in this long (ms). */
private const val LIT_MS = 600

/** A jump takes this long (ms): the words that stood leave in its first part, the new ones come in its last. */
private const val JUMP_MS = 600
private const val JUMP_OUT = 0.4f
private const val JUMP_IN = 0.45f
