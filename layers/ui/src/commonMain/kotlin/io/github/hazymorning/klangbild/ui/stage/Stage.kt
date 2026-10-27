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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.FlowLight
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.stage.Rhythm.apart
import io.github.hazymorning.klangbild.ui.stage.Rhythm.descent
import io.github.hazymorning.klangbild.ui.stage.Rhythm.frame
import io.github.hazymorning.klangbild.ui.stage.Rhythm.together
import io.github.hazymorning.klangbild.ui.theme.Face
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.rememberFigures
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The plain background under everything: black in the dark (on an OLED the pixels are off), the
 * light background's color by day. It reaches [BEYOND_EDGES_DP] past each screen edge, so the
 * screen's drift against burn-in never shows an edge.
 */
@Composable
fun BaseWall(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val m = BEYOND_EDGES_DP * density
        drawRect(color, Offset(-m, -m), Size(size.width + 2 * m, size.height + 2 * m))
    }
}

/**
 * Only the part of [BaseWall] beyond the screen's edges, faded by [alpha]: a new background's
 * color coming up with its light during a change, over the old background.
 */
@Composable
fun WallMargin(color: Color, alpha: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        val a = alpha().coerceIn(0f, 1f)
        if (a <= 0.002f) return@Canvas
        val m = BEYOND_EDGES_DP * density
        val w = size.width
        val h = size.height
        drawRect(color, Offset(-m, -m), Size(m, h + 2 * m), alpha = a)
        drawRect(color, Offset(w, -m), Size(m, h + 2 * m), alpha = a)
        drawRect(color, Offset(0f, -m), Size(w, m), alpha = a)
        drawRect(color, Offset(0f, h), Size(w, m), alpha = a)
    }
}

/** How far every full-screen ground reaches beyond the screen (dp): more than the drift (±6 dp) ever moves it. */
const val BEYOND_EDGES_DP = 24f

/**
 * The wall around the artwork, one static bitmap scaled softly to the screen. In the dark it is
 * the artwork's light ([io.github.hazymorning.klangbild.render.WallGlow]), and [lightLevel] is its
 * brightness in linear light (pause, the Intensity setting: 0 = off, 1 = as designed,
 * 1.45 = strong). In the light it is plain paper. [alpha] fades the layer
 * during a change; [shift] moves the light with the cover flow's cards (dp), the cover's colors
 * stay where they are. In Cover Flow's dark the light fades out before the text column, wherever
 * it stands ([column]: its shift from the grid, dp).
 */
@Composable
fun WallLayer(
    scene: ArtworkScene,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    lightLevel: () -> Float = { 1f },
    shift: () -> Float = { 0f },
    column: () -> Float = { 0f },
) {
    // With the cover colors background, the cover's own colors are the wall.
    if (scene.coverField) return FieldLayer(scene, modifier, alpha, lightLevel)
    val image = scene.wall
    val filters = remember { HashMap<Int, ColorFilter>() }
    val lit = scene.appearance == Appearance.DARK
    val beside = lit && scene.layout.flow
    val density = LocalDensity.current.density
    val shade = remember(density) { columnShade(density) }
    Canvas(modifier.fillMaxSize()) {
        val a = alpha().coerceIn(0f, 1f)
        val l = if (lit) lightLevel().coerceIn(0f, 2f) else 1f
        if (a <= 0.002f || l <= 0.002f) return@Canvas
        // The bitmap is sRGB: a factor k in linear light is k^(1/2.2) on its values.
        val step = (l.pow(1f / 2.2f) * 40f).roundToInt()
        val filter = if (step == 40) null else filters.getOrPut(step) { scaleFilter(step / 40f) }
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
 * size) with softly rounded corners. [coverLevel] (a pause) darkens it through the color matrix, not
 * through transparency. Nothing is drawn around it: no frame, no line, no shadow.
 */
@Composable
fun ArtworkLayer(
    scene: ArtworkScene,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    coverLevel: () -> Float = { 1f },
) {
    val brush = remember(scene) { ShaderBrush(ImageShader(scene.cover, TileMode.Clamp, TileMode.Clamp)) }
    val filters = remember { CoverFilters() }
    val rect = scene.layout.artRect
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) },
    ) {
        val filter = filters.at(coverLevel(), scene.appearance)
        val w = scene.cover.width.toFloat()
        val h = scene.cover.height.toFloat()
        val corner = CornerRadius(scene.corner * density)
        // The cover's pixels start at its corner, which stands on a whole pixel.
        translate((rect.left * density).roundToInt().toFloat(), (rect.top * density).roundToInt().toFloat()) {
            drawRoundRect(brush, Offset.Zero, Size(w, h), corner, colorFilter = filter)
        }
    }
}

/**
 * Color matrix for the artwork: the [Lamp]'s breath of warmth, [level] (a pause) and a gentle
 * highlight compression, so even a white cover never glares on black. White becomes a soft paper
 * tone around L* 88 instead of #FFFFFF; a full-screen-bright print would also make the TV's
 * brightness limiter dim everything. On the light wall a white cover stays a little whiter
 * than the paper around it.
 */
fun coverFilter(level: Float, appearance: Appearance): ColorFilter {
    val (r, g, b) = coverScale(level, appearance)
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/** [coverFilter] by level, in fiftieths, each made once: a pause dims the cover through a few dozen. */
internal class CoverFilters {
    private val filters = HashMap<Int, ColorFilter>()

    fun at(level: Float, appearance: Appearance): ColorFilter {
        val step = (level.coerceIn(0f, 1f) * LEVEL_STEPS).roundToInt()
        return filters.getOrPut(step * 2 + appearance.ordinal) { coverFilter(step / LEVEL_STEPS.toFloat(), appearance) }
    }

    private companion object {
        const val LEVEL_STEPS = 50
    }
}

/** The per-channel scale of [coverFilter] (red, green, blue). */
fun coverScale(level: Float, appearance: Appearance): Triple<Float, Float, Float> {
    val k = level * if (appearance == Appearance.LIGHT) 1.06f else 1f
    val w = Lamp.WARMTH
    return Triple(k * 0.92f * (1f + 0.0375f * w), k * 0.905f * (1f - 0.0066f * w), k * 0.876f * (1f - 0.045f * w))
}


/**
 * The text block beside the artwork, centered on it from the first line's cap height to the
 * last baseline: (work), title, (version), artist, album · year, then the progress bar (or the
 * station, or a notice). What comes next sits apart on the artwork's bottom edge. [alpha] and
 * [rise] animate a change line by line; [textLevel] darkens the text while paused; [shift] moves the
 * column to the right of its place in the grid (dp: a cover flow with cards missing). While the
 * song's words take the column ([StageModel.lyrics]), they stand there instead ([LyricsText]);
 * while the queue shows as a list ([StageModel.queue]), the song stands above it ([QueueText]).
 */
@Composable
internal fun TextLayer(
    model: StageModel,
    modifier: Modifier = Modifier,
    alpha: (TextLine) -> Float = { 1f },
    rise: (TextLine) -> Float = { 0f },
    textLevel: () -> Float = { 1f },
    shift: () -> Float = { 0f },
    queuePlan: QueuePlan? = rememberQueuePlan(model),
) {
    val lyrics = model.lyrics
    if (lyrics != null) {
        LyricsText(model, lyrics, modifier, alpha, rise, textLevel, shift)
        return
    }
    if (queuePlan != null) {
        QueueText(model, queuePlan, modifier, alpha, rise, textLevel)
        return
    }
    val plan = rememberTextPlan(model)
    val styles = plan.styles
    val layout = model.scene.layout
    val t = model.text
    val localDensity = LocalDensity.current
    val colW = (layout.columnWidth * localDensity.density).roundToInt()

    fun line(which: TextLine): Modifier = Modifier.lineMotion(which, alpha, rise)
    val slotText = model.slotText
    val measurer = rememberTextMeasurer(cacheSize = 4)

    Layout(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = textLevel().coerceIn(0f, 1f) },
        content = {
            // 0: work line
            val work = t.work
            if (work != null) BasicText(work, line(TextLine.WORK), style = styles.work, maxLines = 2, overflow = TextOverflow.Ellipsis) else Box(Modifier)
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
                    val fitted = remember(chapter, colW, styles.album) { fitWords(measurer, chapter, styles.album, colW) }
                    BasicText(fitted, style = styles.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                plan.album != null -> BasicText(plan.album, line(TextLine.ALBUM), style = styles.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else -> Box(Modifier)
            }
            // 5: progress bar, station or notice; changes cross-fade
            SlotContent(model, styles, line(TextLine.SLOT), label = "slot")
            // 6: what comes next, on the artwork's bottom edge
            FooterLine(plan.footer, plan.footerLead, styles, line(TextLine.FOOTER))
        },
    ) { measurables, constraints ->
        val c = Constraints(maxWidth = colW)
        val cBottom = Constraints(minWidth = colW, maxWidth = colW)
        val work = measurables[0].measure(c)
        // The title within the constraints it was set in.
        val title = measurables[1].measure(plan.titleConstraints)
        val version = measurables[2].measure(c)
        val artist = measurables[3].measure(c)
        val album = measurables[4].measure(c)
        val bottom = measurables[5].measure(cBottom)
        val nextLine = measurables[6].measure(c)
        fun px(dp: Float) = (dp * density).roundToInt()
        fun capOf(style: TextStyle) = (style.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()

        // Each part: where its cap height is (from its top), where its last baseline is, how far
        // its descenders reach below it, the white above it ([Rhythm]) and its optical indent.
        class Part(val p: Placeable, val capTop: Int, val baseline: Int, val descent: Float, val white: Float, val dx: Int = 0)
        val parts = ArrayList<Part>()
        fun text(p: Placeable, style: TextStyle, white: Float, depth: Float = descent(style)) {
            if (p.height == 0) return
            val first = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            val last = p[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            parts += Part(p, first - capOf(style), last, depth, white, px(opticalIndent(style.fontSize.value)))
        }
        val rhythm = plan.titleRhythm
        text(work, styles.work, 0f)
        text(title, plan.titleStyle, together(styles.work, rhythm), depth = descent(rhythm))
        // The title's group ends the title's descenders below its last line, so who made it
        // stands as far from it whether the version ends it or the title does.
        text(version, plan.versionStyle, together(rhythm, plan.versionStyle), depth = descent(rhythm))
        text(artist, plan.artistStyle, apart(rhythm, plan.artistStyle))
        // The album with the artist, or apart from the title without one.
        text(album, styles.album, if (artist.height > 0) together(plan.artistStyle, styles.album) else apart(rhythm, styles.album))
        if (bottom.height > 0) {
            // The bar block measures from the bar's top to the labels' baseline; a line of
            // text in its place is aligned by its own cap height.
            val isText = slotText != null
            val first = if (isText) bottom[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: bottom.height else 0
            parts += Part(
                bottom,
                capTop = if (isText) first - capOf(styles.label) else 0,
                baseline = if (isText) first else bottom.height,
                descent = 0f,
                white = frame(rhythm, plan.artistStyle),
            )
        }
        // Positions of each cap height, relative to the first one: the white above it from the
        // descenders of the line before.
        var y = 0
        val capY = IntArray(parts.size)
        parts.forEachIndexed { i, part ->
            if (i > 0) {
                val prev = parts[i - 1]
                y = capY[i - 1] - prev.capTop + prev.baseline + (prev.descent + part.white).roundToInt()
            }
            capY[i] = y
        }
        val blockH = parts.lastOrNull()?.let { capY.last() - it.capTop + it.baseline } ?: 0
        val top = (layout.textCenter * density).roundToInt() - blockH / 2
        // The last line's descenders end where the column ends.
        val footer = (layout.footerBottom * density - styles.footer.fontSize.toPx() * Fonts.DESCENT).roundToInt()
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


/**
 * What comes next after its [lead] ("Up next"); a new one (the next chapter, while a book plays
 * on, or the hint for the quick settings) cross-fades in place, and so does one that comes or
 * goes ([text] null: none).
 */
@Composable
internal fun FooterLine(text: String?, lead: String, styles: StageStyles, modifier: Modifier) {
    Crossfade(text?.let { lead to it }, modifier, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer), label = "next") { line ->
        if (line == null) {
            Box(Modifier)
        } else {
            BasicText(footerText(line.first, line.second, styles), style = styles.footer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    shift: () -> Float = { 0f },
    /** How strongly it shows: the status line of the other wall cross-fades with the stage. */
    alpha: (() -> Float)? = null,
) {
    val status = model.statusLine
    if (status.isEmpty) return
    val layout = model.scene.layout
    val styles = rememberStageStyles(model.scene.palette)
    val figures = rememberFigures(styles.time, Face.TITLE)
    val clockText = status.clock
    val measurer = rememberTextMeasurer()
    val localDensity = LocalDensity.current
    val separator = TimeText.SEPARATOR.trimStart()
    // A value after the clock, or after another value, follows a separator.
    fun valueText(i: Int, value: String) = if (clockText == null && i == 0) value else separator + value
    // Which values fit beside the clock and the place, worked out on their measured widths.
    val values = remember(status, styles, layout, localDensity) {
        fun width(text: AnnotatedString, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
        val space = with(localDensity) { styles.label.fontSize.toPx() } * STATUS_SPACE_EM
        var room = layout.columnWidth * localDensity.density
        if (clockText != null) room -= width(figures.of(clockText), styles.time) + space
        status.lead?.let { room -= width(AnnotatedString(separator + it), styles.label) }
        val fit = ArrayList<String>()
        for ((i, v) in status.values.withIndex()) {
            val need = width(AnnotatedString(valueText(i, v)), styles.label) + space
            if (need > room) break
            fit += v
            room -= need
        }
        fit
    }
    val leadAfter = clockText != null || values.isNotEmpty()
    // Faded draw by draw: its parts never overlap, and no layer of the screen's size is needed.
    val fade = if (alpha == null) Modifier else Modifier.graphicsLayer {
        this.alpha = alpha().coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    Layout(
        modifier = modifier.then(fade).fillMaxSize(),
        content = {
            if (clockText != null) BasicText(figures.of(clockText), style = styles.time.copy(color = styles.muted), maxLines = 1) else Box(Modifier)
            values.forEachIndexed { i, v -> BasicText(valueText(i, v), style = styles.label, maxLines = 1) }
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
        val before = if (clock.width > 0) listOf(clock) + parts else parts
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
