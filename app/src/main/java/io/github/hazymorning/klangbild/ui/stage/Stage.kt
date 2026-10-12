package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.domain.TimeLine
import io.github.hazymorning.klangbild.render.FlowLight
import io.github.hazymorning.klangbild.text.texts
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.theme.Face
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.rememberFigures
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The wall under everything: black in the dark (on an OLED the pixels are off), paper in
 * the light. It reaches [BEYOND_EDGES_DP] beyond the screen on every side, so the screen's drift
 * against burn-in never uncovers an edge. [beyondOnly] draws only that margin (a new wall's
 * color coming up with its light during a change); [alpha] fades it.
 */
@Composable
fun BaseWall(color: Color, modifier: Modifier = Modifier, alpha: () -> Float = { 1f }, beyondOnly: Boolean = false) {
    Canvas(modifier.fillMaxSize()) {
        val a = alpha().coerceIn(0f, 1f)
        if (a <= 0.002f) return@Canvas
        val m = BEYOND_EDGES_DP * density
        val w = size.width
        val h = size.height
        if (!beyondOnly) {
            drawRect(color, Offset(-m, -m), Size(w + 2 * m, h + 2 * m), alpha = a)
        } else {
            drawRect(color, Offset(-m, -m), Size(m, h + 2 * m), alpha = a)
            drawRect(color, Offset(w, -m), Size(m, h + 2 * m), alpha = a)
            drawRect(color, Offset(0f, -m), Size(w, m), alpha = a)
            drawRect(color, Offset(0f, h), Size(w, m), alpha = a)
        }
    }
}

/** How far every full-screen ground reaches beyond the screen (dp): more than the drift (±6 dp) ever moves it. */
const val BEYOND_EDGES_DP = 24f

/**
 * The wall around the artwork, one static bitmap scaled softly to the screen. In the dark it is
 * the artwork's light ([io.github.hazymorning.klangbild.render.WallGlow]), and [level] is its
 * brightness in linear light (pause, the Intensity setting: 0 = off, 1 = as designed,
 * 1.45 = strong). In the light it is paper with the artwork's shadow. [alpha] fades the layer
 * during a change; [shift] moves the light with the cover flow's cards (dp), the cover's colors
 * stay where they are. In Cover Flow's dark the light fades out before the text column, wherever
 * it stands ([column]: its shift from the grid, dp).
 */
@Composable
fun WallLayer(
    scene: ArtworkScene,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    level: () -> Float = { 1f },
    shift: () -> Float = { 0f },
    column: () -> Float = { 0f },
) {
    // With the cover colors background, the cover's own colors are the wall.
    if (scene.coverField) return FieldLayer(scene, modifier, alpha, level)
    val image = remember(scene) { scene.wall.asImageBitmap() }
    val filters = remember { HashMap<Int, ColorFilter?>() }
    val lit = scene.appearance == Appearance.DARK
    val beside = lit && scene.layout.view == StageView.FLOW
    val density = LocalDensity.current.density
    val shade = remember(density) { columnShade(density) }
    Canvas(modifier.fillMaxSize()) {
        val a = alpha().coerceIn(0f, 1f)
        val l = if (lit) level().coerceIn(0f, 2f) else 1f
        if (a <= 0.002f || l <= 0.002f) return@Canvas
        // The bitmap is sRGB: a factor k in linear light is k^(1/2.2) on its values.
        val step = (l.pow(1f / 2.2f) * 40f).roundToInt()
        val filter = filters.getOrPut(step) { if (step == 40) null else scaleFilter(step / 40f) }
        drawImage(
            image,
            dstOffset = IntOffset((shift() * density).roundToInt(), 0),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = a,
            colorFilter = filter,
            filterQuality = FilterQuality.Low,
        )
        if (!beside) return@Canvas
        // Black laid over the light from where it starts to fade before the column to beyond the edge.
        val start = (scene.layout.columnLeft + column() - FlowLight.WHOLE_BEFORE_COLUMN) * density
        val m = BEYOND_EDGES_DP * density
        translate(left = start) {
            drawRect(shade, topLeft = Offset(0f, -m), size = Size(size.width - start + m, size.height + 2 * m), alpha = a)
        }
    }
}

/** The black over the cover flow's light toward the text column, from where it starts to fade ([FlowLight.shade]). */
private fun columnShade(density: Float): Brush {
    val ramp = (FlowLight.WHOLE_BEFORE_COLUMN - FlowLight.GONE_BEFORE_COLUMN) * density
    val stops = Array(SHADE_STOPS + 1) { i ->
        val t = i / SHADE_STOPS.toFloat()
        t to Color.Black.copy(alpha = FlowLight.shade(t))
    }
    return Brush.horizontalGradient(*stops, startX = 0f, endX = ramp)
}

/** Steps of the shade's ramp: a smooth curve, closely enough. */
private const val SHADE_STOPS = 24

private fun scaleFilter(k: Float) = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
)

/**
 * The artwork, drawn 1:1 at a whole-pixel position (the bitmap already has its exact display
 * size) with softly rounded corners. [dim] (pause) darkens it through the color matrix, not
 * through transparency; [scale] lets it settle during a change. Nothing is drawn around it:
 * no frame, no line, no shadow.
 */
@Composable
fun ArtworkLayer(
    scene: ArtworkScene,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    dim: () -> Float = { 1f },
    scale: () -> Float = { 1f },
) {
    val shader = remember(scene) {
        android.graphics.BitmapShader(scene.cover, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    val place = remember { android.graphics.Matrix() }
    val filters = remember(scene) { HashMap<Int, ColorFilter>() }
    val rect = scene.coverRect
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha().coerceIn(0f, 1f)
                val s = scale()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(rect.centerX / scene.screen.widthDp, rect.centerY / scene.screen.heightDp)
            },
    ) {
        val step = (dim().coerceIn(0f, 1f) * 50f).roundToInt()
        val filter = filters.getOrPut(step) { coverFilter(step / 50f, scene.appearance) }
        val x = (rect.left * density).roundToInt().toFloat()
        val y = (rect.top * density).roundToInt().toFloat()
        val w = scene.cover.width.toFloat()
        val h = scene.cover.height.toFloat()
        val corner = CornerRadius(scene.corner * density)
        place.setTranslate(x, y)
        shader.setLocalMatrix(place)
        drawRoundRect(brush, Offset(x, y), Size(w, h), corner, colorFilter = filter)
    }
}


/**
 * Large type has more room before its first stem than small type; left as it is, a title
 * looks indented against the lines below it. Pulled left by the difference (dp).
 */
internal fun opticalIndent(size: Float): Float = -(0.065f * (size - 15f)).coerceAtLeast(0f)

/**
 * The text block beside the artwork, centered on it from the first line's cap height to the
 * last baseline: (work), title, (version), artist, album · year, then the progress bar (or the
 * station, or a notice). What comes next sits apart on the artwork's bottom edge. [alpha] and
 * [rise] animate a change line by line; [dim] darkens the text while paused; [shift] moves the
 * column to the right of its place in the grid (dp: a cover flow with cards missing). While the
 * song's words take the column ([StageModel.lyrics]), they stand there instead ([LyricsText]).
 */
@Composable
fun TextLayer(
    model: StageModel,
    modifier: Modifier = Modifier,
    plan: TextPlan = rememberTextPlan(model),
    alpha: (TextLine) -> Float = { 1f },
    rise: (TextLine) -> Float = { 0f },
    dim: () -> Float = { 1f },
    shift: () -> Float = { 0f },
) {
    val lyrics = model.lyrics
    if (lyrics != null) {
        LyricsText(model, lyrics, modifier, alpha, rise, dim, shift)
        return
    }
    val layout = model.scene.layout
    val t = model.text
    val styles = remember(model.scene.palette) { StageStyles(model.scene.palette) }
    val block = plan.styles
    val localDensity = LocalDensity.current
    val colW = (layout.columnWidth * localDensity.density).roundToInt()
    val playing = model.status == PlayerStatus.PLAYING || model.status == PlayerStatus.BUFFERING

    fun line(which: TextLine): Modifier = Modifier.graphicsLayer {
        this.alpha = alpha(which).coerceIn(0f, 1f)
        translationY = (rise(which) * density).roundToInt().toFloat()
    }
    val showBar = model.line == null && model.progress && model.durationMs != null
    val bottomLine = model.line
    val measurer = rememberTextMeasurer(cacheSize = 4)

    Layout(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = dim().coerceIn(0f, 1f) },
        content = {
            // 0: work line
            if (t.work != null) BasicText(t.work, line(TextLine.WORK), style = block.work, maxLines = 2, overflow = TextOverflow.Ellipsis) else Box(Modifier)
            // 1: title
            BasicText(plan.title, line(TextLine.TITLE), style = plan.titleStyle, maxLines = plan.titleLines, overflow = TextOverflow.Ellipsis)
            // 2: version
            if (plan.version != null) BasicText(plan.version, line(TextLine.VERSION), style = plan.versionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
            // 3: artist
            if (plan.artists != null) {
                BasicText(plan.artists, line(TextLine.ARTIST), style = plan.artistStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Box(Modifier)
            }
            // 4: album · year, or the chapter, which changes in place
            when {
                model.chapter != null -> Crossfade(
                    targetState = model.chapter,
                    modifier = line(TextLine.ALBUM),
                    animationSpec = tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer),
                    label = "chapter",
                ) { chapter ->
                    // A long name ends after a whole word, never inside one.
                    val fitted = remember(chapter, colW, block.album) { fitWords(measurer, chapter, block.album, colW) }
                    BasicText(fitted, style = block.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                plan.album != null -> BasicText(plan.album, line(TextLine.ALBUM), style = block.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else -> Box(Modifier)
            }
            // 5: progress bar, station or notice; changes cross-fade
            Crossfade(
                targetState = when {
                    bottomLine != null -> bottomLine
                    showBar -> BAR
                    else -> null
                },
                modifier = line(TextLine.BOTTOM),
                animationSpec = tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer),
                label = "bottom-line",
            ) { content ->
                when (content) {
                    null -> Box(Modifier)
                    BAR -> ProgressBar(
                        durationMs = model.durationMs ?: 1L,
                        positionMs = model.positionMs,
                        startMs = model.rangeStartMs,
                        playing = playing,
                        colors = ProgressColors(track = styles.track, fill = styles.accent, label = styles.quiet),
                        labelStyle = styles.time,
                    )
                    else -> BasicText(content, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 6: what comes next, on the artwork's bottom edge
            if (plan.next != null) NextLine(plan.next, plan.nextLead, styles, line(TextLine.NEXT)) else Box(Modifier)
        },
    ) { measurables, constraints ->
        val c = Constraints(maxWidth = colW)
        val cBottom = Constraints(minWidth = colW, maxWidth = colW)
        val work = measurables[0].measure(c)
        val title = measurables[1].measure(c)
        val version = measurables[2].measure(c)
        val artist = measurables[3].measure(c)
        val album = measurables[4].measure(c)
        val bottom = measurables[5].measure(cBottom)
        val nextLine = measurables[6].measure(c)
        fun px(dp: Float) = (dp * density).roundToInt()
        fun capOf(style: TextStyle) = (style.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()

        // Each part: where its cap height is (from its top), where its last baseline is, and
        // its optical indent.
        class Part(val p: Placeable, val capTop: Int, val baseline: Int, val gap: Int, val dx: Int = 0)
        val parts = ArrayList<Part>()
        fun text(p: Placeable, style: TextStyle, gap: Float) {
            if (p.height == 0) return
            val first = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            val last = p[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            parts += Part(p, first - capOf(style), last, if (parts.isEmpty()) 0 else px(gap), px(opticalIndent(style.fontSize.value)))
        }
        text(work, block.work, 0f)
        text(title, plan.titleStyle, Gaps.WORK)
        text(version, plan.versionStyle, Gaps.VERSION)
        text(artist, plan.artistStyle, Gaps.ARTIST)
        text(album, block.album, Gaps.ALBUM)
        if (bottom.height > 0) {
            // The bar block measures from the bar's top to the labels' baseline; a line of
            // text in its place is aligned by its own cap height.
            val isText = bottomLine != null
            val first = if (isText) bottom[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: bottom.height else 0
            parts += Part(
                bottom,
                capTop = if (isText) first - capOf(styles.label) else 0,
                baseline = if (isText) first else bottom.height,
                gap = if (parts.isEmpty()) 0 else px(Gaps.BOTTOM),
            )
        }
        // Positions of each cap height, relative to the first one.
        var y = 0
        val capY = IntArray(parts.size)
        parts.forEachIndexed { i, part ->
            if (i > 0) {
                val prev = parts[i - 1]
                y = capY[i - 1] - prev.capTop + prev.baseline + part.gap
            }
            capY[i] = y
        }
        val blockH = parts.lastOrNull()?.let { capY.last() - it.capTop + it.baseline } ?: 0
        val top = (layout.textCenter * density).roundToInt() - blockH / 2
        // The last line's descenders end where the column ends.
        val footer = (layout.footerBottom * density - styles.next.fontSize.toPx() * Fonts.DESCENT).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Read while placing: a column that glides is only placed anew.
            val x = ((layout.columnLeft + shift()) * density).roundToInt()
            parts.forEachIndexed { i, part -> part.p.place(x + part.dx, top + capY[i] - part.capTop) }
            if (nextLine.height > 0) {
                val b = nextLine[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: nextLine.height
                nextLine.place(x, footer - b)
            }
        }
    }
}

private const val BAR = "\u0000bar"

/**
 * [text] on one line of [width] px: whole, or cut after its last whole word that leaves room
 * for an ellipsis ("Conversation with Mrs. Grubach, then …"). A single word too long for the
 * line is left to the line's own ellipsis.
 */
internal fun fitWords(measurer: TextMeasurer, text: String, style: TextStyle, width: Int): String {
    fun fits(s: String) = measurer.measure(AnnotatedString(s), style, maxLines = 1, softWrap = false).size.width <= width
    if (fits(text)) return text
    var end = text.length
    while (true) {
        end = text.lastIndexOfAny(charArrayOf(' ', ' '), end - 1)
        if (end <= 0) return text
        // Never end on a dash or a separator: they are trimmed before the ellipsis is added.
        val head = text.substring(0, end).trimEnd(' ', ' ', '–', '·', ',', ':', ';')
        if (head.isNotEmpty() && fits("$head …")) return "$head …"
    }
}

/**
 * What comes next after its [lead] ("Up next"); a new one (the next chapter, while a book plays
 * on, or the hint for the quick settings) cross-fades in place.
 */
@Composable
private fun NextLine(text: String, lead: String, styles: StageStyles, modifier: Modifier) {
    Crossfade(lead to text, modifier, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer), label = "next") { (l, t) ->
        BasicText(nextText(t, styles, l), style = styles.next, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The status line at the top of the column: the time, the values of one's own, then where the
 * music plays and what it is doing ("Living room · Paused", or a passing volume). Its cap
 * height is on the artwork's top edge, and it starts on the column's left edge like everything
 * else. The place has its room first; values that do not fit beside it are left out, from the
 * last, never cut. The place cross-fades when it changes; the clock and the values just tick,
 * the clock's figures in fixed cells. [shift] moves it with the column (dp).
 */
@Composable
fun StatusLayer(
    model: StageModel,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    shift: () -> Float = { 0f },
) {
    val status = model.statusLine
    if (status == null || status.isEmpty) return
    val layout = model.scene.layout
    val styles = remember(model.scene.palette) { StageStyles(model.scene.palette) }
    val figures = rememberFigures(styles.time, Face.TITLE)
    val clockText = status.clock
    val measurer = rememberTextMeasurer()
    val local = LocalDensity.current
    val separator = TimeLine.SEPARATOR.trimStart()
    // Which values fit beside the clock and the place, worked out on their measured widths.
    val values = remember(status, styles, layout, local) {
        fun width(text: AnnotatedString, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
        val space = with(local) { styles.label.fontSize.toPx() } * STATUS_SPACE_EM
        var room = layout.columnWidth * local.density
        if (clockText != null) room -= width(figures.of(clockText), styles.time) + space
        status.lead?.let { room -= width(AnnotatedString(separator + it), styles.label) }
        val fit = ArrayList<String>()
        for ((i, v) in status.values.withIndex()) {
            val need = width(AnnotatedString(if (clockText == null && i == 0) v else separator + v), styles.label) + space
            if (need > room) break
            fit += v
            room -= need
        }
        fit
    }
    val leadAfter = clockText != null || values.isNotEmpty()
    Layout(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) },
        content = {
            if (clockText != null) BasicText(figures.of(clockText), style = styles.time.copy(color = styles.muted), maxLines = 1) else Box(Modifier)
            values.forEachIndexed { i, v ->
                BasicText(if (clockText == null && i == 0) v else separator + v, style = styles.label, maxLines = 1)
            }
            Crossfade(
                targetState = status.lead,
                animationSpec = tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer),
                label = "status-lead",
            ) { lead ->
                if (lead != null) {
                    BasicText(
                        if (leadAfter) separator + lead else lead,
                        style = styles.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Box(Modifier)
                }
            }
        },
    ) { measurables, constraints ->
        val space = (styles.label.fontSize.toPx() * STATUS_SPACE_EM).roundToInt()
        val column = (layout.columnWidth * density).roundToInt()
        val clock = measurables.first().measure(Constraints())
        val parts = measurables.subList(1, measurables.size - 1).map { it.measure(Constraints()) }
        // Everything before the place, with the space after each part.
        val before = listOf(clock).filter { it.width > 0 } + parts
        val used = before.sumOf { it.width + space }
        val lead = measurables.last().measure(Constraints(maxWidth = (column - used).coerceAtLeast(0)))
        fun Placeable.first(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height
        // Cap height where the column begins.
        val baseline = (layout.statusTop * density).roundToInt() + (styles.label.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            var x = ((layout.columnLeft + shift()) * density).roundToInt()
            for (p in before) {
                p.place(x, baseline - p.first())
                x += p.width + space
            }
            if (lead.height > 0) lead.place(x, baseline - lead.first())
        }
    }
}

/** The space between the parts of the status line: a word space of its labels. */
private const val STATUS_SPACE_EM = 0.26f
