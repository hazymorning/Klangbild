package io.github.hazymorning.klangbild.ui.flow

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.FlowCard
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.render.WallGlow
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.stage.coverScale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** A stretch of a change, as shares of its time (0..1). */
@Immutable
class Window(val start: Float, val end: Float) {
    /** How far [t] has come through this stretch (0..1). */
    fun of(t: Float): Float = if (end <= start) (if (t >= end) 1f else 0f) else ((t - start) / (end - start)).coerceIn(0f, 1f)

    companion object {
        val ALL = Window(0f, 1f)
    }
}

/**
 * A card during a change of the strip: it moves from position [from] to [to] (equal when it
 * stays) during [move], and fades from [fromAlpha] to [toAlpha] (a card that comes or goes)
 * during [fade]. A card that [rolls] in from beyond the strip's end crosses the screen at an
 * even pace ([FlowGeometry.rolled]).
 */
@Immutable
class CardMotion(
    val id: Long,
    val card: FlowCard,
    val from: Float,
    val to: Float,
    val fromAlpha: Float = 1f,
    val toAlpha: Float = 1f,
    val move: Window = Window.ALL,
    val fade: Window = Window.ALL,
    val rolls: Boolean = false,
)

/**
 * The cards of the cover flow, drawn in one perspective. Every frame, each card's position
 * is interpolated, its four corners computed ([FlowGeometry]) and its texture drawn as one
 * quad through a perspective matrix, farthest first (cards never cut through each other,
 * see [FlowGeometry]). Nothing is laid out or allocated per frame; color filters are cached
 * by step.
 *
 * @param time how far the change has come (0..1, linear); each card eases its own stretch.
 * @param dim brightness of the cards (pause).
 * @param alpha opacity of the whole strip (a new strip coming up over the old one).
 * @param edges the cards dissolve into the plain wall before the screen's edges. On the
 *   cover's colors, which reach the edges, they pass the edges.
 * @param shift how far the cards stand to the right of their place in the grid (dp), so that
 *   a strip with cards missing stands in the middle ([FlowGeometry.balance]).
 * @param sign a card that stands in the middle card's place (a party's code), facing the room
 *   whatever the strip does; [signAlpha] is how far it has come up. The cards passing the middle
 *   fade beneath it, their mirror images with them.
 */
@Composable
fun FlowStrip(
    cards: List<CardMotion>,
    geometry: FlowGeometry,
    appearance: Appearance,
    wall: Color,
    modifier: Modifier = Modifier,
    time: () -> Float = { 1f },
    dim: () -> Float = { 1f },
    alpha: () -> Float = { 1f },
    edges: Boolean = true,
    shift: () -> Float = { 0f },
    sign: FlowCard? = null,
    signAlpha: () -> Float = { 0f },
) {
    val n = cards.size
    val pos = remember(cards) { FloatArray(n) }
    val alphas = remember(cards) { FloatArray(n) }
    val order = remember(cards) { IntArray(n) }
    // The card the strip turns to: the others never outshine it.
    val middleLightness = remember(cards) { cards.firstOrNull { it.to == 0f && it.toAlpha > 0f }?.card?.lightness ?: 0.5f }
    val tools = remember { DrawTools() }
    val filters = remember(appearance, wall) { HashMap<Int, ColorMatrixColorFilter>() }
    val paper = wall.toArgb()
    Canvas(modifier.fillMaxSize()) {
        val t = time().coerceIn(0f, 1f)
        val d = dim().coerceIn(0f, 1f)
        val all = alpha().coerceIn(0f, 1f)
        val covered = signAlpha().coerceIn(0f, 1f)
        for (i in 0 until n) {
            val c = cards[i]
            val s = Motion.Turn.transform(c.move.of(t))
            val p = if (c.rolls) geometry.rolled(c.from, c.to, s) else c.from + (c.to - c.from) * s
            pos[i] = p
            val fade = c.fromAlpha + (c.toAlpha - c.fromAlpha) * Motion.Sine.transform(c.fade.of(t))
            // Beneath a sign, the cards passing the middle fade, their mirror images with them.
            alphas[i] = fade * geometry.presence(p) * all * (1f - covered * (1f - smoothstep(SIGN_NEAR, SIGN_FAR, abs(p))))
            order[i] = i
        }
        // Farthest first (insertion sort: a handful of cards, no allocation).
        for (i in 1 until n) {
            val k = order[i]
            var j = i - 1
            while (j >= 0 && abs(pos[order[j]]) < abs(pos[k])) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = k
        }
        val dx = shift()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (o in 0 until n) {
                val i = order[o]
                val a = alphas[i]
                if (a <= 0.004f) continue
                val card = cards[i].card
                geometry.corners(pos[i], tools.dp)
                for (k in 0 until 8) tools.dst[k] = (tools.dp[k] + if (k % 2 == 0) dx else 0f) * density
                val light = geometry.light(pos[i]) * d * stepBack(card.lightness, middleLightness, pos[i], appearance)
                val step = (light * STEPS).roundToInt()
                val filter = filters.getOrPut(step) { ColorMatrixColorFilter(cardMatrix(step / STEPS.toFloat(), appearance, paper)) }
                // How much the card is squeezed sideways: far turned cards take the half-size texture.
                val width = hypot(tools.dst[2] - tools.dst[0], tools.dst[3] - tools.dst[1]) / card.sidePx
                val half = 1f - smoothstep(HALF_FROM, HALF_TO, width)
                if (half < 1f) quad(native, card, false, a, filter, tools, snap = pos[i] == 0f)
                if (half > 0f) quad(native, card, true, a * half, filter, tools, snap = false)
            }
            // The sign facing the room in the middle card's place, crisp on whole pixels, lit as the middle card.
            if (sign != null && covered * all > 0.004f) {
                geometry.corners(0f, tools.dp)
                for (k in 0 until 8) tools.dst[k] = (tools.dp[k] + if (k % 2 == 0) dx else 0f) * density
                val step = (geometry.light(0f) * d * STEPS).roundToInt()
                val filter = filters.getOrPut(step) { ColorMatrixColorFilter(cardMatrix(step / STEPS.toFloat(), appearance, paper)) }
                quad(native, sign, false, covered * all, filter, tools, snap = true)
            }
        }
        // The cards dissolve into the wall before the screen's edges (on an OLED the band stays
        // black; on paper the fade is short, so a card leaving does not look bleached). The fade
        // ends before the middle card's place, which a first song brings near the left edge.
        if (!edges) return@Canvas
        val band = WallGlow.EDGE_BLACK_DP * density
        val fadeDp = (if (appearance == Appearance.LIGHT) PAPER_EDGE_FADE_DP else EDGE_FADE_DP) * density
        val reach = min(band + fadeDp, (geometry.cx - geometry.side / 2f + dx) * density)
        drawRect(wall, Offset.Zero, Size(band, size.height))
        drawRect(Brush.horizontalGradient(listOf(wall, wall.copy(alpha = 0f)), startX = band, endX = reach), Offset(band, 0f), Size(reach - band, size.height))
        val bottom = size.height - band
        drawRect(wall, Offset(0f, bottom), Size(size.width, band))
        drawRect(Brush.verticalGradient(listOf(wall.copy(alpha = 0f), wall), startY = bottom - fadeDp, endY = bottom), Offset(0f, bottom - fadeDp), Size(size.width, fadeDp))
    }
}

/**
 * On the dark wall a card beside the middle one also steps back by its brightness. One much
 * lighter than the middle card (a cream sleeve beside a night sky) dims until it is at most a
 * little lighter, never below half, so the middle card stays the heaviest. The effect fades in
 * with the distance from the middle.
 */
private fun stepBack(lightness: Float, middle: Float, position: Float, appearance: Appearance): Float {
    if (appearance != Appearance.DARK) return 1f
    val relative = (STEP_BACK_RATIO * middle / lightness.coerceAtLeast(0.05f)).coerceIn(0.5f, 1f)
    return 1f + (relative - 1f) * min(abs(position), 1f)
}

/** Reused per strip: corners, matrix and paint, so nothing is allocated per frame. */
private class DrawTools {
    val dp = FloatArray(8)
    val dst = FloatArray(8)
    val src = FloatArray(8)
    val matrix = Matrix()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}

/** Draws [card]'s texture (the half-size one if [half]) onto the quad in [tools].dst. */
private fun quad(
    canvas: android.graphics.Canvas,
    card: FlowCard,
    half: Boolean,
    alpha: Float,
    filter: ColorMatrixColorFilter,
    tools: DrawTools,
    snap: Boolean,
) {
    val bitmap = if (half) card.half else card.full
    val scale = if (half) 0.5f else 1f
    val pad = FlowCard.PAD * scale
    val w = card.sidePx * scale
    val h = card.heightPx * scale
    val s = tools.src
    s[0] = pad; s[1] = pad
    s[2] = pad + w; s[3] = pad
    s[4] = pad + w; s[5] = pad + h
    s[6] = pad; s[7] = pad + h
    val m = tools.matrix
    if (snap) {
        // Facing the room, the card is drawn 1:1 on whole pixels, as crisp as the single view.
        m.setTranslate((tools.dst[0] - pad).roundToInt().toFloat(), (tools.dst[1] - pad).roundToInt().toFloat())
    } else {
        m.setPolyToPoly(s, 0, tools.dst, 0, 4)
    }
    tools.paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    tools.paint.colorFilter = filter
    canvas.drawBitmap(bitmap, m, tools.paint)
}

/**
 * The color matrix for a card lit at [light] (1 in the middle, less to the sides and in a
 * pause). On black the card gets darker; on paper it fades toward the paper, the way things
 * farther from the window look in daylight.
 */
private fun cardMatrix(light: Float, appearance: Appearance, paper: Int): ColorMatrix {
    val (r, g, b) = coverScale(1f, appearance)
    return if (appearance == Appearance.DARK) {
        ColorMatrix(
            floatArrayOf(
                r * light, 0f, 0f, 0f, 0f,
                0f, g * light, 0f, 0f, 0f,
                0f, 0f, b * light, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    } else {
        val f = (1f - light) * PAPER_FOG
        val pr = ((paper shr 16) and 0xFF) * f
        val pg = ((paper shr 8) and 0xFF) * f
        val pb = (paper and 0xFF) * f
        ColorMatrix(
            floatArrayOf(
                r * (1f - f), 0f, 0f, 0f, pr,
                0f, g * (1f - f), 0f, 0f, pg,
                0f, 0f, b * (1f - f), 0f, pb,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}

/** A card passing the middle fades beneath a sign there from this far from it (in card places), fully within [SIGN_NEAR]. */
private const val SIGN_NEAR = 0.3f
private const val SIGN_FAR = 0.8f

/** Steps of the cached light levels. */
private const val STEPS = 48

/** How much a card fades into the paper where the light falls off. */
private const val PAPER_FOG = 0.3f

/** A card beside the middle one is at most this much lighter than it (dark wall). */
private const val STEP_BACK_RATIO = 1.1f

/** Squeezed below this share of its width, a card starts to take the half-size texture, fully below [HALF_TO]. */
private const val HALF_FROM = 0.62f
private const val HALF_TO = 0.82f

/** The stretch over which the cards dissolve before the band at the screen's edges (dp): dark, paper. */
private const val EDGE_FADE_DP = 70f
private const val PAPER_EDGE_FADE_DP = 36f
