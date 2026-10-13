package io.github.hazymorning.klangbild.render

import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import kotlin.math.roundToInt

/**
 * Lamp light for the screens without artwork (rest, menu): a warm white bulb. The stage's own
 * light is [WallGlow].
 */
object LampLight {
    /** Light for neutral surroundings: warm white, like a 2700 to 3000 K bulb. */
    val LAMP = Lch(0.70f, 0.045f, 72f)

    /** Hue of plain lamp light. */
    const val LAMP_HUE = 72f

    /** The sRGB tint for a pool of light: [color], a little warmed by the [Lamp], at [peak] luminance. */
    fun lightTint(color: Lch, peak: Float): Int =
        scaledTo(color.toLab().lerp(Lch(color.l, LAMP.c, LAMP.h).toLab(), 0.35f * Lamp.WARMTH).toLch(), peak)

    /** [c] scaled in linear light to the given relative luminance (keeps its chromaticity). */
    private fun scaledTo(c: Lch, luminance: Float): Int {
        val lin = Oklab.toLinear(Oklab.gamutMap(c.toLab()))
        val y = 0.2126f * lin[0] + 0.7152f * lin[1] + 0.0722f * lin[2]
        val k = if (y > 0f) luminance / y else 0f
        fun ch(v: Float) = (Oklab.linearToSrgb((v * k).coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(lin[0]) shl 16) or (ch(lin[1]) shl 8) or ch(lin[2])
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
