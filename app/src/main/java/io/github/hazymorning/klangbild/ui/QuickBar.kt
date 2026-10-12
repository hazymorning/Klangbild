package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.render.HaloLight
import io.github.hazymorning.klangbild.render.ImageOps
import io.github.hazymorning.klangbild.render.LinearImage
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.ui.stage.BEYOND_EDGES_DP
import io.github.hazymorning.klangbild.ui.theme.textType
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Geometry of the quick settings (dp, on the 960 × 540 grid). */
object QuickGrid {
    /** The row starts where the stage's artwork starts, and ends where its text column ends. */
    const val LEFT = 88f
    const val RIGHT = 872f

    /** The values' baseline (well inside the screen's safe area) and the labels' above it. */
    const val VALUE_BASELINE = 495f
    const val LABEL_BASELINE = 468f

    /** The veil that lets the row read over whatever shows. It starts clear here. */
    const val VEIL_TOP = 372f

    /** The veil reaches its full strength here and keeps it down to the edge, so nothing behind the row shows through. */
    const val VEIL_FULL = 448f

    /** Room between two entries. */
    const val GAP = 64f

    /**
     * The light under the chosen entry, a Gaussian. Across, σ is this share of half the entry's
     * width plus a little (dp), so it lights the entry, not its neighbors. Up and down, σ is in dp.
     */
    const val GLOW_SHARE = 0.6f
    const val GLOW_PAD = 8f
    const val GLOW_SIGMA_Y = 15f

    /** How far the row rises when it comes. */
    const val RISE = 22f

    /**
     * A row longer than the room glides, just far enough to show the chosen entry whole; the
     * entries beyond the room's ends fade out over this stretch (dp).
     */
    const val EDGE_FADE = 96f

    /** An entry's name over its value: size (sp) and weight. */
    const val LABEL_SP = 15f
    const val LABEL_WEIGHT = 460
    const val VALUE_SP = 20f
    const val VALUE_WEIGHT = 480
}

/**
 * The quick settings along the bottom edge: they rise a little and fade in when down is
 * pressed, and sink back when they close. Each entry is its name in the quiet ink with its value
 * under it; the chosen one is lit from below by the same soft band of light as the menu's
 * chosen line, its words at full strength (no frame, no chip, no icon). The entries keep their
 * places whatever their values say, so nothing jumps while a value changes.
 */
@Composable
fun QuickBarOverlay(quick: QuickState?, appearance: Appearance, modifier: Modifier = Modifier) {
    // The last state stays while the row sinks away.
    val last = remember { arrayOfNulls<QuickState>(1) }
    if (quick != null) last[0] = quick
    val density = LocalDensity.current.density
    val shown = remember { Animatable(0f) }
    LaunchedEffect(quick != null) {
        shown.animateTo(if (quick != null) 1f else 0f, tween(if (quick != null) 360 else 280, easing = Motion.Dimmer))
    }
    if (quick == null && shown.value <= 0f) return
    val state = quick ?: last[0] ?: return
    // It rises as it fades in. It is faded draw by draw, not through a layer of the screen's size,
    // which would cut its ground off at the screen's edges while it moves.
    QuickBar(state, appearance, modifier.graphicsLayer {
        val v = shown.value
        alpha = v
        translationY = (1f - v) * QuickGrid.RISE * density
        compositingStrategy = CompositingStrategy.ModulateAlpha
    })
}

/** The quick settings' colors: like the menu's, near-neutral, on black or on paper. */
private class QuickColors(
    val veil: Color,
    val band: Color,
    val label: Color,
    val labelFocus: Color,
    val value: Color,
    val valueFocus: Color,
) {
    companion object {
        fun of(appearance: Appearance): QuickColors {
            fun c(l: Float, chroma: Float = 0.004f) = Color(Lamp.applyArgb(Lch(l, chroma, HaloLight.LAMP_HUE).toArgb()))
            return if (appearance == Appearance.DARK) {
                QuickColors(
                    veil = Color.Black,
                    band = Color(HaloLight.lightTint(HaloLight.LAMP, GLOW_PEAK)),
                    label = c(0.56f),
                    labelFocus = c(0.74f),
                    value = c(0.66f),
                    valueFocus = c(0.93f),
                )
            } else {
                QuickColors(
                    veil = c(CoverPalette.PAPER, 0.006f),
                    band = Color(0xFF0D0C0B),
                    label = c(0.52f),
                    labelFocus = c(0.42f),
                    value = c(0.45f),
                    valueFocus = c(0.24f),
                )
            }
        }
    }
}

@Composable
fun QuickBar(quick: QuickState, appearance: Appearance, modifier: Modifier = Modifier) {
    val colors = remember(appearance) { QuickColors.of(appearance) }
    val labelStyle = remember { textType(QuickGrid.LABEL_SP, QuickGrid.LABEL_WEIGHT) }
    val valueStyle = remember { textType(QuickGrid.VALUE_SP, QuickGrid.VALUE_WEIGHT) }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val keys = quick.rows.map { it.key }
    val labels = quick.rows.map { it.label }
    val values = quick.rows.map { it.value }
    // Each entry's place (dp): as wide as its name or its widest value, so a value that changes
    // never moves the others; only the last one is as wide as what it says now (nothing stands
    // after it).
    val density = LocalDensity.current.density
    val widths = remember(keys, labels, values.last(), density) {
        quickWidths(quick, measurer, labelStyle, valueStyle, density)
    }
    val reaches = remember(keys, labels, density) { quickWidths(quick, measurer, labelStyle, valueStyle, density, widest = true) }
    val lefts = widths.runningFold(QuickGrid.LEFT) { x, w -> x + w + QuickGrid.GAP }.dropLast(1)
    val cursor = quick.cursor.coerceIn(0, keys.size - 1)
    // A row longer than the room glides to show the chosen entry whole (by its widest value, so a
    // value that changes never moves the row).
    val glide = remember { Glide() }
    val shift by animateGlide(glide.toShow(lefts[cursor], reaches[cursor]), ROW_GLIDE_MS)
    // The light glides to the chosen entry.
    val glowX by animateGlide(lefts[cursor] + widths[cursor] / 2f, ROW_GLIDE_MS)
    val glowSigma by animateGlide(widths[cursor] / 2f * QuickGrid.GLOW_SHARE + QuickGrid.GLOW_PAD, ROW_GLIDE_MS)
    val glow = rememberOffMain(colors.band) { glowBlob(SceneBuilder.ditherFor(colors.band.toArgb())) }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val d = density
            // The veil reaches beyond the screen's sides and bottom (and below, where the row rises
            // from), so it leaves no gap at the edges, whatever the drift against burn-in.
            val m = BEYOND_EDGES_DP * d
            drawRect(
                Brush.verticalGradient(
                    0f to colors.veil.copy(alpha = 0f),
                    ((QuickGrid.VEIL_FULL - QuickGrid.VEIL_TOP) / (540f - QuickGrid.VEIL_TOP)) to colors.veil,
                    1f to colors.veil,
                    startY = QuickGrid.VEIL_TOP * d,
                    endY = size.height,
                ),
                topLeft = Offset(-m, QuickGrid.VEIL_TOP * d),
                size = Size(size.width + 2 * m, size.height - QuickGrid.VEIL_TOP * d + m + QuickGrid.RISE * d),
            )
            val g = glow ?: return@Canvas
            // The band of light, centered on the value's middle line; it adds to the veil. The
            // blob holds ±3 σ.
            val cy = (QuickGrid.VALUE_BASELINE - QuickGrid.VALUE_SP * 0.35f) * d
            val hw = glowSigma * 3f * d
            val hh = QuickGrid.GLOW_SIGMA_Y * 3f * d
            drawImage(
                g,
                dstOffset = IntOffset(((glowX - shift) * d - hw).roundToInt(), (cy - hh).roundToInt()),
                dstSize = IntSize((2 * hw).roundToInt(), (2 * hh).roundToInt()),
                colorFilter = ColorFilter.tint(colors.band, BlendMode.Modulate),
                filterQuality = FilterQuality.Low,
                blendMode = BlendMode.Plus,
            )
        }
        quick.rows.forEachIndexed { i, row ->
            key(row.key) {
                val focused = i == cursor
                val labelColor by animateColorAsState(if (focused) colors.labelFocus else colors.label, tween(240, easing = Motion.Dimmer), label = "quick-label")
                val valueColor by animateColorAsState(if (focused) colors.valueFocus else colors.value, tween(240, easing = Motion.Dimmer), label = "quick-value")
                val left = lefts[i]
                val width = widths[i]
                // Faded through its colors, not through a layer of the screen's size.
                val seen = { inView(left - shift, width) }
                Entry(x = { left - shift }, labelBaseline = QuickGrid.LABEL_BASELINE, valueBaseline = QuickGrid.VALUE_BASELINE, width = width) {
                    BasicText(row.label, style = labelStyle, color = { labelColor.copy(alpha = labelColor.alpha * seen()) }, maxLines = 1)
                    Crossfade(targetState = row.value, animationSpec = tween(220, easing = Motion.Dimmer), label = "quick-value-text") { v ->
                        BasicText(v, style = valueStyle, color = { valueColor.copy(alpha = valueColor.alpha * seen()) }, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Where the quick settings' row stands (dp it has glided left): it moves only as far as needed
 * to show the chosen entry whole within the room from [QuickGrid.LEFT] to [QuickGrid.RIGHT], and
 * stays where it is while the chosen entry is in view.
 */
private class Glide {
    private var shift = 0f

    /** The shift that shows the entry from [left] wide [width] (dp, unshifted), the chosen one now. */
    fun toShow(left: Float, width: Float): Float {
        if (left + width - shift > QuickGrid.RIGHT) shift = left + width - QuickGrid.RIGHT
        if (left - shift < QuickGrid.LEFT) shift = left - QuickGrid.LEFT
        shift = shift.coerceAtLeast(0f)
        return shift
    }
}

/** How visible an entry from [x] wide [width] (dp, on screen) is: whole within the room, fading out beyond its ends. */
private fun inView(x: Float, width: Float): Float {
    val out = maxOf(QuickGrid.LEFT - x, x + width - QuickGrid.RIGHT, 0f)
    return (1f - out / QuickGrid.EDGE_FADE).coerceIn(0f, 1f)
}

/**
 * The places of the quick settings' entries (dp): each as wide as its name or the widest value
 * it can show, the last as wide as what it says now (or, [widest], as its widest value too).
 */
fun quickWidths(
    quick: QuickState,
    measurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: androidx.compose.ui.text.TextStyle,
    valueStyle: androidx.compose.ui.text.TextStyle,
    density: Float,
    widest: Boolean = false,
): List<Float> = quick.rows.mapIndexed { i, row ->
    fun w(text: String, style: androidx.compose.ui.text.TextStyle) = measurer.measure(AnnotatedString(text), style, maxLines = 1).size.width
    val value = if (i == quick.rows.lastIndex && !widest) w(row.value, valueStyle) else (row.values + row.value).maxOf { w(it, valueStyle) }
    max(w(row.label, labelStyle), value) / density
}

/**
 * An entry: its name with its baseline at [labelBaseline], its value with its baseline at
 * [valueBaseline] (dp), from [x] (read when it is placed, so a gliding row is only placed anew).
 */
@Composable
private fun Entry(x: () -> Float, labelBaseline: Float, valueBaseline: Float, width: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val c = Constraints(maxWidth = ((width + 8f) * density).roundToInt())
        val label = measurables[0].measure(c)
        val value = measurables[1].measure(c)
        fun androidx.compose.ui.layout.Placeable.first(): Int = this[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        layout(constraints.maxWidth, constraints.maxHeight) {
            val left = (x() * density).roundToInt()
            label.place(left, (labelBaseline * density).roundToInt() - label.first())
            value.place(left, (valueBaseline * density).roundToInt() - value.first())
        }
    }
}

/** The band's brightest point (≈ L* 10 on black), like the menu's. */
private const val GLOW_PEAK = 0.011f

/** The row's glide to the chosen entry from rest (ms). */
private const val ROW_GLIDE_MS = 280

private const val BLOB_W = 96
private const val BLOB_H = 48

/** A soft oval of light (a Gaussian, ±3 σ across the bitmap): grayscale, dithered, tinted when drawn. */
private fun glowBlob(ditherLsb: Float): androidx.compose.ui.graphics.ImageBitmap {
    val img = LinearImage(BLOB_W, BLOB_H)
    for (y in 0 until BLOB_H) for (x in 0 until BLOB_W) {
        val u = ((x + 0.5f) / BLOB_W * 2f - 1f) * 3f
        val v = ((y + 0.5f) / BLOB_H * 2f - 1f) * 3f
        val value = exp(-0.5f * (u * u + v * v))
        val o = y * BLOB_W + x
        img.r[o] = value
        img.g[o] = value
        img.b[o] = value
    }
    val px = ImageOps.toArgbDithered(img, seed = 7, ditherLsb = ditherLsb, blackBelow = 0.0005f)
    return Bitmap.createBitmap(px, BLOB_W, BLOB_H, Bitmap.Config.ARGB_8888).asImageBitmap()
}
