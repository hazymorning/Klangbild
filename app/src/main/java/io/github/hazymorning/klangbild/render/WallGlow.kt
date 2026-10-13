package io.github.hazymorning.klangbild.render

import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.color.Oklab
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The light around the artwork: a tight halo in the colors of the artwork's own edges, like
 * a picture lit from behind. A pale print gets warm paper light around it, a synthwave horizon
 * gives pink light below, the sea gives teal light beside it. The color at each point of the
 * wall is the color of the nearest stretch of the artwork's edge, mixed more with distance (as
 * diffuse light is). Its brightness falls off as a Gaussian, the same on every side, and is gone
 * before the text column begins. A picture lights the wall at most as much as it is bright
 * itself: a dark edge gives little light, a black one none.
 *
 * Computed once per scene as a dithered bitmap. Drawing it is one textured rectangle that
 * never moves, so there is no work per frame and nothing that could step.
 */
object WallGlow {
    /** Glow brightness (relative luminance, ≈ L* 17) right at the edge of a bright print. */
    const val EDGE_LUMINANCE = 0.022f

    /** The glow is never more than this share of the edge's own luminance. */
    const val SHARE_OF_EDGE = 0.4f

    /** Width of the halo as a share of the print's size: σ of exp(−(d/σ)²), 26 dp at 368 dp. */
    const val SPREAD = 0.0707f

    /** Shape of the fall-off: a Gaussian. */
    const val FALLOFF_POWER = 2f

    /** The black band along the screen's edges (dp) and the stretch over which the light fades into it. */
    const val EDGE_BLACK_DP = 36f
    const val EDGE_FADE_DP = 30f

    /** The light also fills this far under the artwork's edge (dp), so its anti-aliased rim never shows black. */
    private const val UNDER_EDGE_DP = 2f

    /** Colorfulness of the glow is capped (Oklab chroma): a backlight, not a neon sign. */
    const val MAX_CHROMA = 0.13f

    /**
     * The glow for a print of [shape] whose edge colors come from [thumb] (a small square
     * thumbnail of the artwork, sRGB ARGB, [thumbSize]²), on [screen]. Returns ARGB pixels of
     * [MAP_W] × [MAP_H] covering the whole screen.
     */
    fun field(thumb: IntArray, thumbSize: Int, shape: PrintShape, screen: ScreenSpec): IntArray {
        val edge = edgeColors(thumb, thumbSize)
        val n = edge.size
        // Light is diffuse: a point of the wall further from the print sees a longer stretch
        // of its edge, so the colors mix more with distance and there are no streaks.
        val levels = BLUR_LEVELS.map { r -> circularBlur(edge, r * n / 192f) }
        val img = LinearImage(MAP_W, MAP_H)
        val sx = screen.widthDp / MAP_W
        val sy = screen.heightDp / MAP_H
        val sigma = SPREAD * shape.rect.width
        val rect = shape.rect
        val samplesPerDp = n / (4f * rect.width)
        val rgb = FloatArray(3)
        for (y in 0 until MAP_H) {
            val py = (y + 0.5f) * sy
            for (x in 0 until MAP_W) {
                val px = (x + 0.5f) * sx
                val signed = shape.signedDistance(px, py)
                if (signed <= -UNDER_EDGE_DP) continue // behind the print: dark
                val d = max(signed, 0f)
                val under = if (signed >= 0f) 1f else smoothstep(-UNDER_EDGE_DP, 0f, signed)
                // The horizontal and vertical fades are multiplied, which is smooth everywhere. (The
                // minimum of the four edge distances would crease along the diagonals, like a miter.)
                val fade = smoothstep(EDGE_BLACK_DP, EDGE_BLACK_DP + EDGE_FADE_DP, min(px, screen.widthDp - px)) *
                    smoothstep(EDGE_BLACK_DP, EDGE_BLACK_DP + EDGE_FADE_DP, min(py, screen.heightDp - py))
                if (fade <= 0f) continue
                val fall = exp(-(d / sigma).toDouble().pow(FALLOFF_POWER.toDouble())).toFloat() * fade * under
                if (fall < 1e-4f) continue
                // The nearest point of the outline, as a position 0..1 along its four sides.
                val qx = px.coerceIn(rect.left, rect.right)
                val qy = py.coerceIn(rect.top, rect.bottom)
                val u = (qx - rect.left) / rect.width
                val v = (qy - rect.top) / rect.height
                val pos = outline(u, v, n)
                // Blur radius along the edge ≈ 0.7 × the distance (in samples), between the levels.
                colorAt(levels, pos, (2f + 0.7f * d) * samplesPerDp, rgb)
                val o = y * MAP_W + x
                img.r[o] = rgb[0] * fall
                img.g[o] = rgb[1] * fall
                img.b[o] = rgb[2] * fall
            }
        }
        return ImageOps.toArgbDithered(img, seed = 13, ditherLsb = 1.1f, blackBelow = 0.0004f)
    }

    /** Blur radii (in samples of a 192-sample outline) of the precomputed edge-color levels. */
    private val BLUR_LEVELS = floatArrayOf(1f, 2f, 4f, 8f, 16f, 32f)

    /** The color at outline position [pos] blurred by about [radius] samples (interpolated between the levels). */
    private fun colorAt(levels: List<Array<FloatArray>>, pos: Float, radius: Float, out: FloatArray) {
        val n = levels[0].size
        val scaled = radius * 192f / n
        var li = 0
        while (li < BLUR_LEVELS.size - 1 && BLUR_LEVELS[li + 1] <= scaled) li++
        val hi = min(li + 1, BLUR_LEVELS.size - 1)
        val t = if (hi == li) 0f else ((scaled - BLUR_LEVELS[li]) / (BLUR_LEVELS[hi] - BLUR_LEVELS[li])).coerceIn(0f, 1f)
        val i0 = pos.toInt()
        val f = pos - i0
        val a = Math.floorMod(i0, n)
        val b = Math.floorMod(i0 + 1, n)
        for (ch in 0..2) {
            val lo = levels[li][a][ch] * (1f - f) + levels[li][b][ch] * f
            val up = levels[hi][a][ch] * (1f - f) + levels[hi][b][ch] * f
            out[ch] = lo + (up - lo) * t
        }
    }

    /** A Gaussian blur (σ = [radius] samples) along the closed outline. */
    private fun circularBlur(edge: Array<FloatArray>, radius: Float): Array<FloatArray> {
        val n = edge.size
        val reach = (radius * 3f).toInt().coerceAtMost(n / 2)
        val weights = FloatArray(2 * reach + 1) { k -> exp(-((k - reach) * (k - reach)) / (2f * radius * radius)) }
        val total = weights.sum()
        return Array(n) { i ->
            val c = FloatArray(3)
            for (k in -reach..reach) {
                val w = weights[k + reach] / total
                val s = edge[Math.floorMod(i + k, n)]
                c[0] += s[0] * w
                c[1] += s[1] * w
                c[2] += s[2] * w
            }
            c
        }
    }

    /** Map size: one pixel per dp on the 960 × 540 dp canvas, scaled up softly when drawn. */
    const val MAP_W = 960
    const val MAP_H = 540

    /**
     * The colors along the print's outline, clockwise from the top-left corner, as linear
     * RGB light at the glow's edge brightness. Each sample is the blurred edge region of the
     * artwork at [EDGE_LUMINANCE] (or at [SHARE_OF_EDGE] of its own luminance, if that is less),
     * with its colorfulness capped and a little warmed by the [Lamp].
     */
    fun edgeColors(thumb: IntArray, size: Int): Array<FloatArray> {
        // A blurred copy: the glow takes the color of a region, not of single pixels.
        val lin = LinearImage.fromArgb(thumb, size, size)
        ImageOps.blur(lin, radius = max(1, size / 16))
        val perSide = size
        val out = Array(perSide * 4) { FloatArray(3) }
        val inset = max(1, size / 12) // a little inside the edge: the first pixels are often a border
        for (i in 0 until perSide * 4) {
            val side = i / perSide
            val t = (i % perSide).toFloat() / (perSide - 1)
            val (x, y) = when (side) {
                0 -> (t * (size - 1)) to 0f
                1 -> (size - 1).toFloat() to t * (size - 1)
                2 -> ((1f - t) * (size - 1)) to (size - 1).toFloat()
                else -> 0f to (1f - t) * (size - 1)
            }
            val xi = x.toInt().coerceIn(inset, size - 1 - inset)
            val yi = y.toInt().coerceIn(inset, size - 1 - inset)
            val o = yi * size + xi
            out[i] = light(lin.r[o], lin.g[o], lin.b[o])
        }
        return out
    }

    /** One edge color as glow light (linear RGB). */
    private fun light(r: Float, g: Float, b: Float): FloatArray {
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        var lab = Oklab.fromLinear(r, g, b)
        // Cap the color, then pull it a little toward the lamp's warm light.
        if (lab.chroma > MAX_CHROMA) lab = Lab(lab.l, lab.a * MAX_CHROMA / lab.chroma, lab.b * MAX_CHROMA / lab.chroma)
        lab = lab.lerp(Lab(lab.l, 0.012f, 0.045f), 0.3f * Lamp.WARMTH)
        val lin = Oklab.toLinear(Oklab.gamutMap(Lab(0.7f, lab.a, lab.b)))
        val y = 0.2126f * lin[0] + 0.7152f * lin[1] + 0.0722f * lin[2]
        val target = min(EDGE_LUMINANCE, SHARE_OF_EDGE * lum)
        val k = if (y > 1e-5f) target / y else 0f
        return floatArrayOf(lin[0] * k, lin[1] * k, lin[2] * k)
    }

    /** Position (in samples, clockwise from the top-left corner) of the outline point (u, v). */
    private fun outline(u: Float, v: Float, n: Int): Float {
        val perSide = n / 4
        val last = perSide - 1
        // Which side is nearest: top, right, bottom, left.
        val dTop = v
        val dRight = 1f - u
        val dBottom = 1f - v
        val dLeft = u
        val m = min(min(dTop, dRight), min(dBottom, dLeft))
        return when (m) {
            dTop -> u * last
            dRight -> perSide + v * last
            dBottom -> 2 * perSide + (1f - u) * last
            else -> 3 * perSide + (1f - v) * last
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
