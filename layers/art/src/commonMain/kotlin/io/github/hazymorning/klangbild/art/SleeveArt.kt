package io.github.hazymorning.klangbild.art

import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Artwork for music without a cover: a part of Klangbild's mark (four rounded squares, each
 * turned a little further, around a ring) on a dark, matte sleeve. The lines are the sleeve's
 * own color in gloss and show only where they catch the lamp, in a broad, soft band across the
 * sleeve, like spot varnish on soft-touch paper. Each stands a little off the paper and has its
 * own thickness of varnish.
 *
 * The foil the lines catch the light in follows [Seed.foil], the view of the mark [Seed.view]:
 * one artist's records share their foil and differ in their view. Without a view the rings
 * rise from below, like a signal; without a foil (an input like a TV's sound, which has only a
 * title) they are plain silver on black.
 *
 * Pure Kotlin (ARGB pixels), the same on every platform, drawn at the exact size it is shown:
 * every edge is worked out from the mark's geometry, so it is smooth at any size.
 */
object SleeveArt {

    /** What the artwork follows: the name its foil comes from and the name its view comes from. */
    data class Seed(val foil: String?, val view: String?)

    /** A view of the mark: its size against the sleeve's side and where its middle stands (shares of the side). */
    data class View(val zoom: Float, val x: Float, val y: Float)

    /**
     * The views of the mark: in each, every line keeps clear of the sleeve's edges by a tenth of
     * its side or crosses them steeply, never along them ([SleeveArtTest]). The last is for
     * what has no view of its own.
     */
    val views = listOf(
        View(2.4f, 0.90f, 0.90f),
        View(2.2f, 0.88f, 0.54f),
        View(2.6f, 0.94f, 0.32f),
        View(3.2f, 0.86f, 0.78f),
        View(2.2f, 0.60f, 0.90f),
    )
    val rising = View(2.2f, 0.46f, 0.90f)

    /** A foil: the metal where it catches the light (lightness, chroma, hue in Oklab), and the hue the sleeve leans to. */
    private class Foil(l: Float, c: Float, h: Float, ground: Float, sleeveChroma: Float = SLEEVE_C) {
        val metal: FloatArray = Oklab.toLinear(Lch(l, c, h).toLab())
        val sleeve: FloatArray = Oklab.toLinear(Lch(SLEEVE_L, sleeveChroma, ground).toLab())
    }

    // Champagne, copper, rose gold, bronze, silver, sage and steel, as on printed sleeves.
    private val foils = listOf(
        Foil(0.90f, 0.040f, 85f, 80f),
        Foil(0.84f, 0.075f, 48f, 40f),
        Foil(0.87f, 0.050f, 22f, 10f),
        Foil(0.82f, 0.060f, 70f, 65f),
        Foil(0.92f, 0.004f, 250f, 250f),
        Foil(0.86f, 0.040f, 140f, 150f),
        Foil(0.86f, 0.035f, 240f, 245f),
    )
    private val plain = Foil(0.92f, 0f, 0f, 0f, sleeveChroma = 0f)

    /** The mark from tools/logo.svg (a 1024 canvas): each line's half size, corner, stroke and turn, from the ring outward. */
    private class Line(val half: Float, val corner: Float, val stroke: Float, turn: Float, val varnish: Float) {
        val cos = cos(-turn * PI.toFloat() / 180f)
        val sin = sin(-turn * PI.toFloat() / 180f)
    }
    private val ringRadius = 83.968f
    private val lines = listOf(
        Line(0f, 0f, 19.456f, 0f, 1.00f),
        Line(135.992f, 90.211f, 18.432f, 9.913f, 0.65f),
        Line(197.041f, 97.537f, 17.408f, 6.094f, 0.85f),
        Line(262.034f, 105.336f, 16.384f, 2.653f, 0.55f),
        Line(329.728f, 113.459f, 15.360f, 0f, 0.75f),
    )

    /** Which foil [seed] gets (an index), or null for plain silver on black. */
    fun foilOf(seed: Seed): Int? = seed.foil?.let { index(it, foils.size) }

    /** Which view of the mark [seed] gets. */
    fun viewOf(seed: Seed): View = seed.view?.let { views[index(it, views.size)] } ?: rising

    private fun index(name: String, count: Int): Int = (stableHash(name.trim().lowercase()).toLong() and 0xFFFFFFFFL).rem(count).toInt()

    /** How many lines the mark has, and the centerline of the [index]th from the ring outward at (x, y) in logo units. */
    internal val lineCount get() = lines.size
    internal fun centerline(index: Int, x: Float, y: Float): Float = centerline(lines[index], x, y)

    /** The centerline of [line] at (x, y) (logo units from the mark's middle): signed distance, negative inside. */
    private fun centerline(line: Line, x: Float, y: Float): Float {
        if (line.half == 0f) return hypot(x, y) - ringRadius
        val qx = abs(x * line.cos - y * line.sin) - line.half + line.corner
        val qy = abs(x * line.sin + y * line.cos) - line.half + line.corner
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - line.corner
    }

    /** [side] × [side] ARGB pixels for [seed]. */
    fun render(seed: Seed, side: Int): IntArray {
        val foil = foilOf(seed)?.let { foils[it] } ?: plain
        val metal = foil.metal
        val sleeve = foil.sleeve
        val view = viewOf(seed)
        val k = 1024f / view.zoom       // logo units across the side
        val px = k / side                 // one pixel in logo units
        val out = IntArray(side * side)
        val rgb = FloatArray(3)
        for (j in 0 until side) {
            val v = (j + 0.5f) / side
            for (i in 0 until side) {
                val u = (i + 0.5f) / side
                // The matte sleeve, lit evenly, a little more from the upper left.
                val diffuse = 1f + DIFFUSE * (1f - u - v)
                for (c in 0..2) rgb[c] = sleeve[c] * diffuse
                // The lamp's reflection: a broad band across the middle, where the gloss catches it.
                val d = (u - 0.5f) * 0.78f + (v - 0.5f) * 0.62f
                val spec = exp(-(d / BAND) * (d / BAND))
                val x = (u - view.x) * k
                val y = (v - view.y) * k
                for (line in lines) {
                    val half = line.stroke * THIN / 2f
                    val edge = abs(centerline(line, x, y)) - half
                    if (edge > RELIEF_REACH * px) continue
                    val cover = (0.5f - edge / px).coerceIn(0f, 1f)
                    val t = line.varnish
                    // Gloss: the sleeve's color a little deeper, mirroring the lamp where it falls.
                    for (c in 0..2) {
                        val lit = sleeve[c] * diffuse * GLOSS + metal[c] * (SHINE * spec * t + SHEEN * t)
                        rgb[c] = rgb[c] * (1f - cover) + lit * cover
                    }
                    // Relief: light on one side of the line, a breath of shade on the other.
                    val o = RELIEF_OFFSET * px
                    val relief = soft(line, x - o, y - o, half, px) - soft(line, x + o, y + o, half, px)
                    val shade = 1f - RELIEF_SHADE * t * max(-relief, 0f)
                    val light = RELIEF_LIGHT * t * max(relief, 0f) * (0.4f + spec)
                    for (c in 0..2) rgb[c] = rgb[c] * shade + metal[c] * light
                }
                out[j * side + i] = argb(rgb, i, j)
            }
        }
        return out
    }

    /** [line]'s coverage at (x, y) with an edge a little softer than a pixel, for its relief. */
    private fun soft(line: Line, x: Float, y: Float, half: Float, px: Float): Float =
        (0.5f - (abs(centerline(line, x, y)) - half) / (px * RELIEF_SOFTNESS)).coerceIn(0f, 1f)

    /** Linear light to an opaque ARGB pixel, dithered below one step of the screen so no gradient bands. */
    private fun argb(rgb: FloatArray, i: Int, j: Int): Int {
        val n = fract(52.982918f * fract(0.06711056f * i + 0.00583715f * j)) - 0.5f
        var p = 0xFF shl 24
        for (c in 0..2) {
            val s = (Oklab.linearToSrgb(rgb[c]) * 255f + n).roundToInt().coerceIn(0, 255)
            p = p or (s shl (16 - 8 * c))
        }
        return p
    }

    private fun fract(x: Float): Float = x - floor(x)

    /** The sleeve: just lighter than the dark wall, a breath of the foil's hue. */
    private const val SLEEVE_L = 0.16f
    private const val SLEEVE_C = 0.022f

    /** The lines at this share of the logo's own strokes: hairlines on the sleeve. */
    private const val THIN = 0.32f

    /** How much more light the upper left of the sleeve gets than the lower right. */
    private const val DIFFUSE = 0.035f

    /** The lamp's reflection: its width (share of the side), the gloss's depth, its shine and the little it shows outside the band. */
    private const val BAND = 0.23f
    private const val GLOSS = 0.86f
    private const val SHINE = 0.12f
    private const val SHEEN = 0.012f

    /** The relief: how far it reaches beyond a line (px), its offset and softness, its shade and light. */
    private const val RELIEF_REACH = 3f
    private const val RELIEF_OFFSET = 0.8f
    private const val RELIEF_SOFTNESS = 2.2f
    private const val RELIEF_SHADE = 0.06f
    private const val RELIEF_LIGHT = 0.022f
}
