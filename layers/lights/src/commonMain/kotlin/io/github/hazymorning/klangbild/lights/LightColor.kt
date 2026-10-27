package io.github.hazymorning.klangbild.lights

import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.math.smoothstep
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The Oklab color of [rgb] (linear sRGB), with a negative channel as 0. */
internal fun labOf(rgb: FloatArray): Lab = Oklab.fromLinear(rgb[0].coerceAtLeast(0f), rgb[1].coerceAtLeast(0f), rgb[2].coerceAtLeast(0f))

/** A color as lights take it: CIE 1931 xy (brightness goes separately). */
data class Xy(val x: Float, val y: Float) {
    fun distance(o: Xy): Float = kotlin.math.hypot(x - o.x, y - o.y)
}

/**
 * The screen's colors as light. Home Assistant turns the chromaticity worked out here into
 * whatever the light takes (its own color space and gamut, or a color temperature).
 *
 * Light on a wall looks far paler than the same color on a screen, and a soft color that is
 * plain on the screen is next to white as light (a pale sky blue, a muted green). So the light
 * takes the color's hue at its purest (the edge of what a screen can show) and goes a good part
 * of the way from white toward it, further the more colorful the color is. Only real grays
 * become white: a neutral one by day, warmer at night, never the amber of a 2700 K bulb, which
 * reads as orange. Very dark colors keep their hue but give it softly.
 */
object LightColor {
    /** Below this chroma (Oklab) a color is a gray and gets white light. */
    const val GRAY_CHROMA = 0.012f

    /** From this chroma on a color is fully a color. */
    const val COLOR_CHROMA = 0.035f

    /** How far from white toward the hue's purest color the light goes, at the least. */
    const val SATURATION_MIN = 0.42f

    /** How much further it goes per unit of chroma, up to [SATURATION_MAX]. */
    const val SATURATION_GAIN = 3.2f
    const val SATURATION_MAX = 0.92f

    /** The white for grays: by day (K) and at night. */
    const val WHITE_DAY_K = 4_300f
    const val WHITE_NIGHT_K = 3_000f

    /** The coolest white a light with white only is given (K). */
    const val WHITE_COOL_K = 6_500f

    /** A light with white only takes this share of the way from white toward the color, as its warmth. */
    const val WHITE_ONLY_SHARE = 0.5f

    /** The color for the light from [rgb] (linear sRGB, 0..1); [night] 0 by day to 1 at night. */
    fun of(rgb: FloatArray, night: Float): Xy {
        val lch = labOf(rgb).toLch()
        val white = white(night)
        val pure = pureXy(lch.h) ?: return white
        // Very dark colors give their hue softly: no glowing navy from a black cover.
        val reach = min(SATURATION_MIN + lch.c * SATURATION_GAIN, if (lch.l < DARK_L) DARK_SATURATION else SATURATION_MAX)
        val color = Xy(white.x + (pure.x - white.x) * reach, white.y + (pure.y - white.y) * reach)
        // Grays become white, blended on the way to color.
        val c = smoothstep(GRAY_CHROMA, COLOR_CHROMA, lch.c)
        return Xy(white.x + (color.x - white.x) * c, white.y + (color.y - white.y) * c)
    }

    /**
     * The color temperature for a light that only has white (K, within [minK] to [maxK]): the
     * warmth of a color halfway between white and [rgb]'s light, by McCamy's approximation of
     * the correlated color temperature. A warm cover gives warm white, a cool one cool white.
     */
    fun kelvin(rgb: FloatArray, night: Float, minK: Int, maxK: Int): Int {
        val white = white(night)
        val xy = of(rgb, night)
        val x = white.x + (xy.x - white.x) * WHITE_ONLY_SHARE
        val y = white.y + (xy.y - white.y) * WHITE_ONLY_SHARE
        val n = (x - 0.3320) / (0.1858 - y)
        val cct = 449 * n * n * n + 3_525 * n * n + 6_823.3 * n + 5_520.33
        val low = max(WHITE_NIGHT_K.toInt(), minK)
        val high = min(WHITE_COOL_K.toInt(), maxK).coerceAtLeast(low)
        return cct.roundToInt().coerceIn(low, high)
    }

    /** xy of the most colorful color of hue [h] (Oklab, degrees) a screen can show; null if there is none. */
    fun pureXy(h: Float): Xy? {
        var best: Lab? = null
        var bestC = -1f
        for (l in CUSP_LIGHTNESS) {
            val lab = Oklab.gamutMap(Lch(l, 0.4f, h).toLab())
            val c = lab.chroma
            if (c > bestC) {
                bestC = c
                best = lab
            }
        }
        return best?.let { xyOf(it) }
    }

    /** How bright the light may be for this color (a share of the level): dark covers give less light. */
    fun dimFor(rgb: FloatArray): Float = 0.5f + 0.5f * smoothstep(0.08f, 0.35f, labOf(rgb).l)

    /** xy of an Oklab color; null for black. */
    fun xyOf(lab: Lab): Xy? {
        val rgb = Oklab.toLinear(lab)
        val r = rgb[0].coerceIn(0f, 1f)
        val g = rgb[1].coerceIn(0f, 1f)
        val b = rgb[2].coerceIn(0f, 1f)
        val x = 0.4124f * r + 0.3576f * g + 0.1805f * b
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val z = 0.0193f * r + 0.1192f * g + 0.9505f * b
        val sum = x + y + z
        return if (sum <= 1e-6f) null else Xy(x / sum, y / sum)
    }

    /** The white for grays at [night] (0 by day to 1 at night). */
    private fun white(night: Float): Xy = whiteXy(WHITE_DAY_K + (WHITE_NIGHT_K - WHITE_DAY_K) * night.coerceIn(0f, 1f))

    /** White light of [kelvin] (1,667 to 25,000 K) on the Planckian locus (Kim et al.). */
    fun whiteXy(kelvin: Float): Xy {
        val t = kelvin.coerceIn(1_667f, 25_000f).toDouble()
        val x = if (t <= 4_000) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2_222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4_000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return Xy(x.toFloat(), y.toFloat())
    }

    /** Home Assistant's brightness (1 to 255) from a share of full brightness; never below [FLOOR]. */
    fun brightness(share: Float): Int = max(FLOOR, (share * 255f).roundToInt()).coerceAtMost(255)

    /** The lowest brightness the colors are shown at: some lights treat lower levels as off. */
    const val FLOOR = 8

    private const val DARK_L = 0.18f
    private const val DARK_SATURATION = 0.45f

    /** Where to look for a hue's most colorful color: blue peaks dark, yellow light. */
    private val CUSP_LIGHTNESS = floatArrayOf(0.45f, 0.55f, 0.65f, 0.75f, 0.85f, 0.95f)
}
