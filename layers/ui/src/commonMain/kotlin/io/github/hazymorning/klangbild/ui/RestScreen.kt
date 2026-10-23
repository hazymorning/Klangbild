package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.ImageOps
import io.github.hazymorning.klangbild.render.LampLight
import io.github.hazymorning.klangbild.render.LinearImage
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.imageBitmapOf
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.text.BalancedText
import io.github.hazymorning.klangbild.ui.theme.ClockFigures
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.rememberClockFigures
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlinx.datetime.TimeZone
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Quiet colors for screens without a cover: soft white on black, or near-black on warm
 * paper. Every line reads (the quietest at least 4.5:1).
 */
data class QuietColors(val wall: Color, val primary: Color, val secondary: Color, val label: Color) {
    companion object {
        fun of(appearance: Appearance = Appearance.DARK): QuietColors {
            fun lit(l: Float, ch: Float) = Lamp.apply(Lch(l, ch, Lamp.HUE).toLab())
            return if (appearance == Appearance.DARK) {
                fun c(l: Float) = Color(lit(l, 0.004f).toArgb())
                QuietColors(wall = Color.Black, primary = c(0.84f), secondary = c(0.68f), label = c(0.62f))
            } else {
                val wall = lit(CoverPalette.PAPER, 0.006f).toArgb()
                val most = CoverPalette.lightestInkOn(wall)
                fun c(l: Float, step: Float) = lit(l, 0.004f).let { Color(Lab(minOf(it.l, most - step), it.a, it.b).toArgb()) }
                QuietColors(wall = Color(wall), primary = c(0.28f, 0.16f), secondary = c(0.42f, 0.08f), label = c(0.5f, 0f))
            }
        }
    }
}

object RestTexts {
    /** What the rest screen says besides the time. */
    data class Lines(
        /** A short label ("Living room · Paused"). */
        val label: String?,
        /** A calm sentence, only when something needs explaining. */
        val sentence: String?,
    )

    fun lines(reason: RestReason, speaker: String?, entityId: String?, t: Texts): Lines = when (reason) {
        RestReason.IDLE -> Lines(speaker, null)
        RestReason.PAUSED -> Lines(listOfNotNull(speaker, t[Strings.statusPaused]).joinToString(TimeText.SEPARATOR), null)
        RestReason.CONNECTING -> Lines(speaker, t[Strings.restConnecting])
        RestReason.OFFLINE -> Lines(null, t[Strings.restOffline])
        RestReason.UNAVAILABLE -> Lines(speaker ?: t[Strings.restSpeaker], t[Strings.restUnavailable])
        RestReason.ENTITY_MISSING -> Lines(null, t.format(Strings.restEntityMissing, entityId ?: t[Strings.restThisSpeaker]))
    }
}

/**
 * The rest screen's light: a soft oval of warm light behind the clock, both exactly in the
 * screen's middle: a faint light, never off.
 */
object RestLight {
    const val MAP_W = 480
    const val MAP_H = 270

    /** Radii of the pool of light (dp); it reaches black at 1.6 × these. */
    private const val RX = 275f
    private const val RY = 175f

    /** The oval vignette reaches black this far from the screen edges (dp). */
    private const val EDGE_DP = 36f

    /**
     * Grayscale light field for the whole screen (sRGB-encoded, dithered), brightest at
     * [cx], [cy] (dp), black well before the screen edges.
     */
    fun field(cx: Float, cy: Float, screen: ScreenSpec, ditherLsb: Float): IntArray {
        val img = LinearImage(MAP_W, MAP_H)
        val sx = screen.widthDp / MAP_W
        val sy = screen.heightDp / MAP_H
        for (y in 0 until MAP_H) for (x in 0 until MAP_W) {
            val px = (x + 0.5f) * sx
            val py = (y + 0.5f) * sy
            val u = (px - cx) / RX
            val v = (py - cy) / RY
            val q = u * u + v * v
            val reach = (1f - q / (1.6f * 1.6f)).coerceAtLeast(0f)
            // Round all the way: an oval vignette keeps the screen edges black, too.
            val ex = (px - screen.widthDp / 2f) / (screen.widthDp / 2f - EDGE_DP)
            val ey = (py - screen.heightDp / 2f) / (screen.heightDp / 2f - EDGE_DP)
            val vignette = smoothstep(1f, 0.2f, ex * ex + ey * ey)
            val value = exp(-q * 1.4f) * reach * reach * vignette
            val o = y * MAP_W + x
            img.r[o] = value; img.g[o] = value; img.b[o] = value
        }
        return ImageOps.toArgbDithered(img, seed = 11, ditherLsb = ditherLsb, blackBelow = 0.0005f)
    }

    /** Brightest point of the light (relative luminance). */
    const val PEAK = 0.014f
}

/**
 * Nothing is playing: a quiet clock on a dim, lamp-lit wall, the date, and one line that
 * says why (never an error code). The clock stands exactly in the screen's middle, measured
 * on what is seen (from the top of the figures to their baseline); the lines hang under it.
 */
@Composable
fun RestScreen(
    reason: RestReason,
    speaker: String?,
    entityId: String?,
    nowMs: Long,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    /** The lamp's strength from the settings (0 = off). */
    intensity: Float = 1f,
    appearance: Appearance = Appearance.DARK,
) {
    val colors = remember(appearance) { QuietColors.of(appearance) }
    val t = texts()
    val lines = RestTexts.lines(reason, speaker, entityId, t)
    // A lamp only lights a dark wall.
    val peak = (RestLight.PEAK * intensity).takeIf { it > 0f && appearance == Appearance.DARK }
    val tint = remember(peak) { peak?.let { LampLight.lightTint(LampLight.LAMP, it) } }
    val screen = ScreenSpec(960, 540, 1f)

    Box(modifier.fillMaxSize()) {
        BaseWall(colors.wall)
        val field = rememberOffMain(tint) {
            tint?.let {
                val px = RestLight.field(screen.widthDp / 2f, screen.heightDp / 2f, screen, ImageOps.ditherFor(it))
                imageBitmapOf(px, RestLight.MAP_W, RestLight.MAP_H)
            }
        }
        if (field != null && tint != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    field,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    colorFilter = ColorFilter.tint(Color(tint), BlendMode.Modulate),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        RestBlock(
            time = t.clockFigures(nowMs, zone),
            date = t.date(nowMs, zone),
            label = lines.label,
            sentence = lines.sentence,
            colors = colors,
        )
    }
}

/**
 * The clock and the lines under it, each centered on the screen's vertical axis, with the
 * clock's figures centered on its horizontal one.
 */
@Composable
private fun RestBlock(time: String, date: String?, label: String?, sentence: String?, colors: QuietColors) {
    val faces = faces()
    val clock = faces.titleType(CLOCK_SP, 320, colors.primary, lineHeight = 140f, tracking = -0.01f)
    Layout(
        content = {
            // The figures keep their own widths (in cells this large the "1" would stand apart);
            // a new minute dissolves in, so the re-centered line never jumps.
            Crossfade(targetState = time, Modifier.layoutId(CLOCK), animationSpec = tween(700, easing = Motion.Dimmer), label = "minute") { t ->
                ClockFace(t, clock)
            }
            if (date != null) {
                BasicText(date, Modifier.layoutId(DATE), style = faces.textType(22f, 420, colors.secondary).merge(TextStyle(textAlign = TextAlign.Center)))
            }
            if (label != null) {
                BasicText(label, Modifier.layoutId(LABEL), style = faces.textType(16f, 500, colors.label).merge(TextStyle(textAlign = TextAlign.Center)))
            }
            if (sentence != null) {
                BalancedText(
                    AnnotatedString(sentence),
                    faces.textType(19f, 420, colors.secondary, lineHeight = 27f).merge(TextStyle(textAlign = TextAlign.Center)),
                    Modifier.layoutId(SENTENCE).widthIn(max = 480.dp),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = measurables.map { it.layoutId to it.measure(loose) }
        // Baselines one under the other (px, from the clock's baseline): the gap before each line
        // is measured from the previous baseline to this line's cap height.
        val baselines = FloatArray(placed.size)
        var last = 0f
        for ((i, pair) in placed.withIndex()) {
            val (id, p) = pair
            if (i > 0) {
                val (size, gap) = when (id) {
                    DATE -> 22f to 22f
                    LABEL -> 16f to 30f
                    else -> 19f to (if (placed[i - 1].first == LABEL) 14f else 30f)
                }
                last += (gap + CAP * size).sp.toPx()
            }
            baselines[i] = last
            // A line of several rows ends on its last baseline.
            if (i > 0) last += (p[LastBaseline] - p[FirstBaseline]).toFloat()
        }
        // The clock's figures (from their top to the baseline) in the very middle; the quieter
        // lines hang under it.
        val origin = constraints.maxHeight / 2f + (CAP * CLOCK_SP).sp.toPx() / 2f
        layout(constraints.maxWidth, constraints.maxHeight) {
            for ((i, pair) in placed.withIndex()) {
                val p = pair.second
                val x = (constraints.maxWidth - p.width) / 2
                val y = origin + baselines[i] - p[FirstBaseline]
                p.place(x, y.roundToInt())
            }
        }
    }
}

/**
 * One minute's figures ([ClockFigures]), drawn as outlines in a box that ends where their ink
 * ends, so a layout centers what is seen; the baseline is marked for the lines under it.
 */
@Composable
private fun ClockFace(time: String, style: TextStyle) {
    val figures = rememberClockFigures(time, style)
    val outline = figures.outline
    val ink = figures.ink
    val color = style.color
    val above = ceil(-ink.top).toInt()
    val width = ceil(ink.width).toInt()
    Layout(
        Modifier
            .semantics { contentDescription = time }
            .drawBehind { translate((width - ink.width) / 2f - ink.left, above.toFloat()) { drawPath(outline, color) } },
    ) { _, _ ->
        layout(width, above + ceil(ink.bottom).toInt(), mapOf(FirstBaseline to above, LastBaseline to above)) {}
    }
}

private const val CLOCK = "clock"
private const val DATE = "date"
private const val LABEL = "label"
private const val SENTENCE = "sentence"

/** The clock's size (sp) and both faces' cap height (share of the size; the figures stand as high). */
private const val CLOCK_SP = 132f
private const val CAP = 0.70f
