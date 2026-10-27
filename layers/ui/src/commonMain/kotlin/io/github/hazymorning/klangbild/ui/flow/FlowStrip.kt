package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.FlowCard
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.render.WallGlow
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.progress
import io.github.hazymorning.klangbild.ui.stage.coverScale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A stretch of a change, as shares of its time (0..1). */
@Immutable
class Window(val start: Float, val end: Float) {
    /** How far [t] has come through this stretch (0..1). */
    fun of(t: Float): Float = progress(t, start, end)

    companion object {
        val ALL = Window(0f, 1f)
    }
}

/**
 * A card during a change of the strip: it moves from position [from] to [to] (equal when it
 * stays) during [move], and fades from [fromAlpha] to [toAlpha] (a card that comes or goes)
 * during [fade]. A card that [rolls] in from beyond the strip's end crosses the screen at an
 * even pace ([FlowGeometry.rolled]). While the strip takes a new look ([FlowLook]), the card's
 * texture before, [was], gives way to [card].
 */
@Immutable
class CardMotion(
    val card: FlowCard,
    val from: Float,
    val to: Float,
    val fromAlpha: Float = 1f,
    val toAlpha: Float = 1f,
    val move: Window = Window.ALL,
    val fade: Window = Window.ALL,
    val rolls: Boolean = false,
    val was: FlowCard? = null,
) {
    /** The card's lightness while its texture before gives way to the new one by [look] (0..1). */
    fun lightness(look: Float): Float = was?.let { it.lightness + (card.lightness - it.lightness) * look } ?: card.lightness
}

/**
 * How the strip looked before it took a new look (a better cover, a song of the same picture
 * whose cover differs, the other wall): the wall its cards stood on, whether they dissolved
 * before the screen's edges, and the sign in the middle card's place.
 */
@Immutable
class FlowLook(val appearance: Appearance, val wall: Color, val edges: Boolean, val sign: FlowCard?)

/**
 * The cards of the cover flow, drawn in one perspective. Every frame, each card's position
 * is interpolated, its four corners computed ([FlowGeometry]) and its texture drawn as one
 * quad through a perspective matrix, farthest first (cards never cut through each other,
 * see [FlowGeometry]). Nothing is laid out or allocated per frame; color filters are cached
 * by step.
 *
 * A card that turns between its full and its half-size texture, or takes a new look, is the
 * mix of its textures laid over the wall as one ([mix]): its half-transparent mirror image keeps
 * its strength all the way. Each texture is lit as on the wall it was made for, so a card of the
 * look before keeps its light while it gives way.
 *
 * @param time how far the change has come (0..1, linear); each card eases its own stretch.
 * @param dim brightness of the cards (pause).
 * @param edges the cards dissolve into the plain wall before the screen's edges. On the
 *   cover's colors, which reach the edges, they pass the edges.
 * @param shift how far the cards stand to the right of their place in the grid (dp), so that
 *   a strip with cards missing stands in the middle ([FlowGeometry.balance]).
 * @param sign a card that stands in the middle card's place (a party's code), facing the room
 *   whatever the strip does; [signAlpha] is how far it has come up. The cards passing the middle
 *   fade beneath it, their mirror images with them.
 * @param before how the strip looked before a new look, and [look] how far the new one has come
 *   (0..1): each card goes from its texture before ([CardMotion.was]) to its new one.
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
    edges: Boolean = true,
    shift: () -> Float = { 0f },
    sign: FlowCard? = null,
    signAlpha: () -> Float = { 0f },
    before: FlowLook? = null,
    look: () -> Float = { 1f },
) {
    val n = cards.size
    val pos = remember(cards) { FloatArray(n) }
    val alphas = remember(cards) { FloatArray(n) }
    val order = remember(cards) { IntArray(n) }
    // The card the strip turns to: the others never outshine it. While the strip turns, the
    // measure goes from the card leaving the middle to the one arriving, as it arrives, so a
    // light card beside a dark middle one never brightens before the strip has moved.
    val arriving = remember(cards) { cards.firstOrNull { it.to == 0f && it.toAlpha > 0f } }
    val leaving = remember(cards) { cards.firstOrNull { it.from == 0f && it.fromAlpha > 0f } }
    val tools = remember { DrawTools() }
    val edgeBands = remember { EdgeBands() }
    val lights = remember(appearance, wall) { Lights(appearance, wall.toArgb()) }
    val lightsBefore = remember(before) { before?.let { Lights(it.appearance, it.wall.toArgb()) } }
    Canvas(modifier.fillMaxSize()) {
        val t = time().coerceIn(0f, 1f)
        val d = dim().coerceIn(0f, 1f)
        val covered = signAlpha().coerceIn(0f, 1f)
        // How far a new look has come: the textures before are drawn only until it has.
        val come = if (before == null) 1f else look().coerceIn(0f, 1f)
        val old = if (come < 1f) before else null
        val arrived = arriving?.let { Motion.Turn.transform(it.move.of(t)) } ?: 1f
        val arrivingLightness = arriving?.lightness(come) ?: NO_MIDDLE_LIGHTNESS
        val leavingLightness = leaving?.lightness(come) ?: arrivingLightness
        val middleLightness = leavingLightness + (arrivingLightness - leavingLightness) * arrived
        for (i in 0 until n) {
            val c = cards[i]
            val s = Motion.Turn.transform(c.move.of(t))
            val p = if (c.rolls) geometry.rolled(c.from, c.to, s) else c.from + (c.to - c.from) * s
            pos[i] = p
            val fade = c.fromAlpha + (c.toAlpha - c.fromAlpha) * Motion.Sine.transform(c.fade.of(t))
            // Beneath a sign, the cards passing the middle fade, their mirror images with them.
            alphas[i] = fade * geometry.presence(p) * (1f - covered * (1f - smoothstep(SIGN_NEAR, SIGN_FAR, abs(p))))
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
            for (o in 0 until n) {
                val i = order[o]
                val a = alphas[i]
                if (a <= UNSEEN) continue
                val c = cards[i]
                val card = c.card
                val p = pos[i]
                tools.place(geometry, p, dx, density)
                val lit = geometry.light(p) * d
                val lightness = c.lightness(come)
                // How much the card is squeezed sideways: far turned cards take the half-size texture.
                val width = hypot(tools.dst[2] - tools.dst[0], tools.dst[3] - tools.dst[1]) / card.sidePx
                val half = 1f - smoothstep(HALF_FROM, HALF_TO, width)
                val was = c.was?.takeIf { old != null }
                val now = if (was == null) 1f else come
                tools.clear()
                // A card that belongs to the look before (it leaves with it) is lit as it was.
                val own = if (card.appearance != appearance && lightsBefore != null) lightsBefore else lights
                val filter = own.filter(lit * stepBack(lightness, middleLightness, p, card.appearance))
                tools.add(card, false, now * (1f - half), filter)
                tools.add(card, true, now * half, filter)
                if (was != null && lightsBefore != null) {
                    val filterBefore = lightsBefore.filter(lit * stepBack(lightness, middleLightness, p, was.appearance))
                    tools.add(was, false, (1f - now) * (1f - half), filterBefore)
                    tools.add(was, true, (1f - now) * half, filterBefore)
                }
                mix(canvas, tools, a, snap = p == 0f)
            }
            // The sign facing the room in the middle card's place, crisp on whole pixels, lit as the middle card.
            val signBefore = old?.sign?.takeIf { it !== sign }
            if ((sign != null || signBefore != null) && covered > UNSEEN) {
                tools.place(geometry, 0f, dx, density)
                val lit = geometry.light(0f) * d
                tools.clear()
                // Until the new wall's sign is made, the one before stands in: lit as before, so it
                // never jumps.
                val signLights = if (sign != null && sign.appearance != appearance && lightsBefore != null) lightsBefore else lights
                if (sign != null) tools.add(sign, false, if (signBefore == null) 1f else come, signLights.filter(lit))
                if (signBefore != null && lightsBefore != null) tools.add(signBefore, false, if (sign == null) 1f else 1f - come, lightsBefore.filter(lit))
                mix(canvas, tools, covered, snap = true)
            }
        }
        // The cards dissolve into the wall before the screen's edges (on an OLED the band stays
        // black; on paper the fade is short, so a card leaving does not look bleached). The fade
        // ends before the middle card's place, which a first song brings near the left edge.
        // During a new look the band goes over from the wall before to the new one, as the wall does.
        val edge = (if (edges) come else 0f) + (if (old?.edges == true) 1f - come else 0f)
        if (edge <= 0f) return@Canvas
        val ink = (if (old == null) wall else over(old.wall, wall, come)).let { if (edge < 1f) it.copy(alpha = it.alpha * edge) else it }
        val band = WallGlow.EDGE_BLACK_DP * density
        val fade = (if (old == null) edgeFade(appearance) else edgeFade(old.appearance) + (edgeFade(appearance) - edgeFade(old.appearance)) * come) * density
        val reach = min(band + fade, (geometry.cx - geometry.side / 2f + dx) * density)
        edgeBands.draw(this, ink, band, reach, fade)
    }
}

/**
 * The bands along the screen's left and bottom edges in which the cards dissolve: solid [band] px
 * of the background's color, then a fade over [fade] px, the left one ending at [reach] px. The
 * gradients are made anew only when one of these changes, so a frame at rest allocates nothing.
 */
private class EdgeBands {
    // What the gradients were made for.
    private var madeInk = Color.Unspecified
    private var madeBand = Float.NaN
    private var madeReach = Float.NaN
    private var madeFade = Float.NaN
    private var madeHeight = Float.NaN
    private lateinit var side: Brush
    private lateinit var foot: Brush

    fun draw(scope: DrawScope, ink: Color, band: Float, reach: Float, fade: Float) = with(scope) {
        val bottom = size.height - band
        if (ink != madeInk || band != madeBand || reach != madeReach || fade != madeFade || size.height != madeHeight) {
            side = Brush.horizontalGradient(listOf(ink, ink.copy(alpha = 0f)), startX = band, endX = reach)
            foot = Brush.verticalGradient(listOf(ink.copy(alpha = 0f), ink), startY = bottom - fade, endY = bottom)
            madeInk = ink
            madeBand = band
            madeReach = reach
            madeFade = fade
            madeHeight = size.height
        }
        drawRect(ink, Offset.Zero, Size(band, size.height))
        drawRect(side, Offset(band, 0f), Size(reach - band, size.height))
        drawRect(ink, Offset(0f, bottom), Size(size.width, band))
        drawRect(foot, Offset(0f, bottom - fade), Size(size.width, fade))
    }
}

/** The stretch over which the cards dissolve before the band at the screen's edges, on [appearance]'s wall (dp). */
private fun edgeFade(appearance: Appearance) = if (appearance == Appearance.LIGHT) PAPER_EDGE_FADE_DP else EDGE_FADE_DP

/** [to] laid over [from] at [alpha], as the screen blends (in sRGB), so the band matches the wall cross-fading beneath it. */
private fun over(from: Color, to: Color, alpha: Float) = Color(
    red = from.red + (to.red - from.red) * alpha,
    green = from.green + (to.green - from.green) * alpha,
    blue = from.blue + (to.blue - from.blue) * alpha,
    alpha = from.alpha + (to.alpha - from.alpha) * alpha,
)

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

/** The cards' color filters on one wall, cached by step of light. */
private class Lights(private val appearance: Appearance, private val paper: Int) {
    private val cache = HashMap<Int, ColorFilter>()

    fun filter(light: Float): ColorFilter {
        val step = (light * STEPS).roundToInt()
        return cache.getOrPut(step) { ColorFilter.colorMatrix(cardMatrix(step / STEPS.toFloat(), appearance, paper)) }
    }
}

/**
 * Reused per strip: corners, matrix and paints, and the textures a card is mixed from this frame
 * (at most four: its full and half-size texture, and those of its look before), so nothing is
 * allocated per frame.
 */
private class DrawTools {
    val dp = FloatArray(8)
    val dst = FloatArray(8)
    val src = FloatArray(8)
    val painter = cardPainter()

    val textures = arrayOfNulls<FlowCard>(4)
    val halves = BooleanArray(4)
    val weights = FloatArray(4)
    val filters = arrayOfNulls<ColorFilter>(4)
    var count = 0

    /** Puts the quad of a card at [position] into [dst] (px), moved right by [dx] dp. */
    fun place(geometry: FlowGeometry, position: Float, dx: Float, density: Float) {
        geometry.corners(position, dp)
        for (j in 0 until 8) dst[j] = (dp[j] + if (j % 2 == 0) dx else 0f) * density
    }

    fun clear() {
        count = 0
    }

    /** Adds [card]'s texture (the half-size one if [half]) with its share [weight] of the mix, lit by [filter]. */
    fun add(card: FlowCard, half: Boolean, weight: Float, filter: ColorFilter) {
        if (weight <= 0f) return
        textures[count] = card
        halves[count] = half
        weights[count] = weight
        filters[count] = filter
        count++
    }
}

/**
 * Draws the textures in [tools] onto the quad in [tools].dst at [alpha], each with its share of
 * the mix, as the mix itself would be laid over what lies beneath. One texture is one quad.
 *
 * A card turning between its own two textures is drawn without a layer, since that happens on
 * every turn. On the dark wall, laying one over the other would count its half-transparent
 * mirror image twice, so what lies beneath (the black floor) is darkened once by the card and
 * then each texture adds its share of light, which is exactly the mix. On paper there is no mirror
 * image, and the print is opaque, so the full texture goes beneath, just strong enough that the
 * half-size one laid over it leaves the card at [alpha], and the edges stay as crisp as at rest.
 *
 * Textures of different cards (a new look: another cover, which may have clear parts, or the other
 * wall) are mixed in a small layer of the card's own, exactly whatever they hold. That happens
 * only now and then, for a moment.
 */
private fun mix(canvas: Canvas, tools: DrawTools, alpha: Float, snap: Boolean) {
    val n = tools.count
    if (n == 0) return
    val first = tools.textures[0]!!
    if (n == 1) {
        quad(canvas, first, tools.halves[0], alpha, tools.filters[0]!!, tools, snap = snap && !tools.halves[0])
        return
    }
    var own = true
    for (j in 1 until n) if (tools.textures[j] !== first) own = false
    if (own && first.appearance == Appearance.DARK) {
        quad(canvas, first, tools.halves[0], alpha, null, tools, snap = snap && !tools.halves[0], blend = CardBlend.BENEATH)
        for (j in 0 until n) {
            quad(canvas, tools.textures[j]!!, tools.halves[j], alpha * tools.weights[j], tools.filters[j]!!, tools, snap = snap && !tools.halves[j], blend = CardBlend.ADD)
        }
    } else if (own) {
        // Full beneath at a(1 - h) / (1 - a h), half over at a h: together exactly a, for any a.
        val full = if (tools.halves[0]) 1 else 0
        val half = 1 - full
        val over = alpha * tools.weights[half]
        val beneath = if (over >= 1f) 0f else (alpha * tools.weights[full] / (1f - over)).coerceIn(0f, 1f)
        quad(canvas, first, false, beneath, tools.filters[full]!!, tools, snap = snap)
        quad(canvas, first, true, over, tools.filters[half]!!, tools, snap = false)
    } else {
        val d = tools.dst
        tools.painter.layer(
            canvas,
            min(min(d[0], d[2]), min(d[4], d[6])) - LAYER_MARGIN_PX,
            min(min(d[1], d[3]), min(d[5], d[7])) - LAYER_MARGIN_PX,
            max(max(d[0], d[2]), max(d[4], d[6])) + LAYER_MARGIN_PX,
            max(max(d[1], d[3]), max(d[5], d[7])) + LAYER_MARGIN_PX,
            (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
        )
        for (j in 0 until n) {
            quad(canvas, tools.textures[j]!!, tools.halves[j], tools.weights[j], tools.filters[j]!!, tools, snap = snap && !tools.halves[j], blend = if (j == 0) CardBlend.OVER else CardBlend.ADD)
        }
        tools.painter.restore(canvas)
    }
}

/** Draws [card]'s texture (the half-size one if [half]) onto the quad in [tools].dst, blended as [blend] says. */
private fun quad(
    canvas: Canvas,
    card: FlowCard,
    half: Boolean,
    alpha: Float,
    filter: ColorFilter?,
    tools: DrawTools,
    snap: Boolean,
    blend: CardBlend = CardBlend.OVER,
) {
    val image = if (half) card.half else card.full
    val scale = if (half) 0.5f else 1f
    val pad = FlowCard.PAD * scale
    val w = card.sidePx * scale
    val h = card.heightPx * scale
    val s = tools.src
    s[0] = pad; s[1] = pad
    s[2] = pad + w; s[3] = pad
    s[4] = pad + w; s[5] = pad + h
    s[6] = pad; s[7] = pad + h
    val k = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    if (snap) {
        // Facing the room, the card is drawn 1:1 on whole pixels, as crisp as the single cover.
        tools.painter.moved(canvas, image, (tools.dst[0] - pad).roundToInt().toFloat(), (tools.dst[1] - pad).roundToInt().toFloat(), k, filter, blend)
    } else {
        tools.painter.mapped(canvas, image, s, tools.dst, k, filter, blend)
    }
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

/** A card or a sign fainter than this, about one step of 8-bit alpha, is not drawn. */
private const val UNSEEN = 0.004f

/** The middle card's lightness the side cards step back from while no card arrives in the middle. */
private const val NO_MIDDLE_LIGHTNESS = 0.5f

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

/** A card's own layer reaches this far beyond its quad, so its filtered edges are never cut (px). */
private const val LAYER_MARGIN_PX = 4f

/** The stretch over which the cards dissolve before the band at the screen's edges (dp): dark, paper. */
private const val EDGE_FADE_DP = 70f
private const val PAPER_EDGE_FADE_DP = 36f
