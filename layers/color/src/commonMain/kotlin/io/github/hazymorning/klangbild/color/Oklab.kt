package io.github.hazymorning.klangbild.color

import io.github.hazymorning.klangbild.math.degrees
import io.github.hazymorning.klangbild.math.radians
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A color in Oklab (Björn Ottosson, 2020): lightness [l], [a] from green to red, [b] from blue to yellow. */
data class Lab(val l: Float, val a: Float, val b: Float) {
    val chroma: Float get() = hypot(a, b)

    /** Hue angle in degrees, 0..360. Meaningless for (near) neutral colors. */
    val hue: Float
        get() {
            val h = degrees(atan2(b, a).toDouble()).toFloat()
            return if (h < 0f) h + 360f else h
        }

    fun toLch(): Lch = Lch(l, chroma, hue)

    fun toArgb(alpha: Int = 255): Int = Oklab.toArgb(this, alpha)

    /** Squared perceptual distance. */
    fun distanceSq(o: Lab): Float {
        val dl = l - o.l
        val da = a - o.a
        val db = b - o.b
        return dl * dl + da * da + db * db
    }

    fun lerp(o: Lab, t: Float): Lab = Lab(l + (o.l - l) * t, a + (o.a - a) * t, b + (o.b - b) * t)
}

/** Oklab in polar form: lightness [l], chroma [c] and hue [h] in degrees. */
data class Lch(val l: Float, val c: Float, val h: Float) {
    fun toLab(): Lab {
        val rad = radians(h.toDouble())
        return Lab(l, (c * cos(rad)).toFloat(), (c * sin(rad)).toFloat())
    }

    fun toArgb(alpha: Int = 255): Int = Oklab.toArgb(toLab(), alpha)
}

/**
 * Conversions between sRGB and Oklab, and the mapping into the sRGB gamut. Palette decisions
 * happen in Oklab, where equal steps look equally large.
 */
object Oklab {
    private val toLinearTable = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** The 8-bit sRGB channel in the low byte of [channel] in linear light. */
    fun srgbToLinear(channel: Int): Float = toLinearTable[channel and 0xFF]

    /** An sRGB-encoded value 0..1 in linear light. */
    fun srgbToLinear(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** Linear light 0..1 encoded as sRGB 0..1. */
    fun linearToSrgb(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else (1.055f * c.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f)
    }

    fun fromArgb(argb: Int): Lab = fromLinear(
        srgbToLinear(argb shr 16),
        srgbToLinear(argb shr 8),
        srgbToLinear(argb),
    )

    fun fromLinear(r: Float, g: Float, b: Float): Lab {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return Lab(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    /** Linear sRGB, possibly out of gamut (values outside 0..1). */
    fun toLinear(lab: Lab): FloatArray {
        val l_ = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
        val m_ = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
        val s_ = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b
        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_
        return floatArrayOf(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
        )
    }

    private fun isInGamut(lab: Lab, eps: Float = 1e-4f): Boolean {
        val rgb = toLinear(lab)
        return rgb.all { it >= -eps && it <= 1f + eps }
    }

    /**
     * Maps a color into sRGB by reducing chroma at constant lightness and hue
     * (binary search). Keeps hues honest instead of clipping channels.
     */
    fun gamutMap(lab: Lab): Lab {
        if (isInGamut(lab)) return lab
        val l = lab.l.coerceIn(0f, 1f)
        var lo = 0f
        var hi = 1f
        val a = lab.a
        val b = lab.b
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (isInGamut(Lab(l, a * mid, b * mid))) lo = mid else hi = mid
        }
        return Lab(l, a * lo, b * lo)
    }

    fun toArgb(lab: Lab, alpha: Int = 255): Int {
        val rgb = toLinear(gamutMap(lab))
        val r = (linearToSrgb(rgb[0]) * 255f).roundToInt().coerceIn(0, 255)
        val g = (linearToSrgb(rgb[1]) * 255f).roundToInt().coerceIn(0, 255)
        val bl = (linearToSrgb(rgb[2]) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
