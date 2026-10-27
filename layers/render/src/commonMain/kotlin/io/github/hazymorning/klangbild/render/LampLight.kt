package io.github.hazymorning.klangbild.render

import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import kotlin.math.roundToInt

/**
 * A warm lamp's light: the glows on screens without a cover (rest, menu, quick settings), and the
 * Cover Flow light behind a gray top edge ([FlowLight]).
 */
object LampLight {
    /** The lamp's color: a warm white (about 4500 K). */
    val LAMP = Lch(0.70f, 0.045f, Lamp.HUE)

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
}
