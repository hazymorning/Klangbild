package io.github.hazymorning.klangbild.lights

import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.FieldScene
import kotlin.math.floor
import kotlin.math.sqrt

/** Where a light stands: left of the screen, behind its middle (above it), right of it. */
enum class LightSlot { LEFT, CENTER, RIGHT }

/**
 * One color from the cover for each light: the field's strongest colors (weighted by area,
 * chroma and lightness), kept apart and ordered as they sit from left to right. A cover of one
 * color adds two neighboring hues, a gray one a warmer and a cooler white. With motion, the
 * colors pass from light to light, resting for most of each step ([HOLD]).
 */
class LightColors(field: FieldScene, private val moving: Boolean) {
    /** Three to five colors (linear sRGB): the first three in slot order, the rest join when the colors move on. */
    private val colors: List<FloatArray> = palette(field)

    /** The color (linear sRGB, 0..1) for the light at [slot] at the field's time [t] (seconds). */
    fun color(slot: LightSlot, t: Double, out: FloatArray) {
        val n = colors.size
        val i = slot.ordinal
        if (!moving) {
            colors[i].copyInto(out)
            return
        }
        // One step along the lights every ROTATE_S of the field's time; most of it resting.
        val p = t / ROTATE_S + i
        val k = floor(p)
        val f = smoothstep(HOLD, 1f, (p - k).toFloat())
        val a = colors[k.toLong().mod(n.toLong()).toInt()]
        val b = colors[(k.toLong() + 1).mod(n.toLong()).toInt()]
        mixOklab(a, b, f, out)
    }

    companion object {
        /**
         * The colors move on by one light in this much of the field's time (s): at medium motion
         * about every two and a half minutes.
         */
        const val ROTATE_S = 90.0

        /** The share of each step a color rests before it passes on. */
        const val HOLD = 0.7f

        /** Two colors closer than this (Oklab) are one. */
        private const val SAME = 0.09f

        /** A cover of one color: its neighbors this far round the hue circle (degrees). */
        private const val NEIGHBOR_HUE = 32f

        /** Three colors for the lights, and two more that join when the colors move on. */
        private const val MAX_COLORS = 5

        /** The cover's colors for the lights: the most telling ones, kept apart, ordered as they sit from left to right. */
        private fun palette(field: FieldScene): List<FloatArray> {
            class Candidate(val rgb: FloatArray, val lab: Lab, val x: Float, var weight: Float)
            val found = ArrayList<Candidate>()
            fun add(rgb: FloatArray, x: Float, weight: Float) {
                val lab = labOf(rgb)
                // Color counts for more than size: a small red patch tells more than a big gray.
                val telling = weight * (0.25f + lab.chroma * 6f) * (0.4f + lab.l)
                val same = found.firstOrNull { sqrt(it.lab.distanceSq(lab)) < SAME }
                if (same != null) {
                    same.weight += telling
                    return
                }
                found += Candidate(rgb.copyOf(), lab, x, telling)
            }
            for (b in field.blobs) add(b.light, b.homeX, b.alpha * b.radius * b.radius * if (b.glow) 0.6f else 1f)
            add(field.baseLight, 0.5f, 0.05f)
            val chosen = found.sortedByDescending { it.weight }.take(MAX_COLORS).toMutableList()
            // Fewer than three: the strongest color's neighbors round the hue circle, or for a
            // gray cover whites a little warmer and cooler.
            if (chosen.size < 3) {
                val lead = chosen.firstOrNull()?.lab?.toLch() ?: Lch(0.7f, 0f, 0f)
                val extra = if (lead.c < LightColor.GRAY_CHROMA) {
                    // Whites a breath warmer and cooler: just past what the light shows as gray.
                    listOf(Lch(lead.l, 0.018f, 70f), Lch(lead.l, 0.018f, 250f))
                } else {
                    listOf(Lch(lead.l, lead.c, lead.h - NEIGHBOR_HUE), Lch(lead.l, lead.c, lead.h + NEIGHBOR_HUE))
                }
                for ((n, lch) in extra.withIndex()) {
                    if (chosen.size >= 3) break
                    val lab = Oklab.gamutMap(lch.toLab())
                    chosen += Candidate(Oklab.toLinear(lab), lab, if (n == 0) 0f else 1f, 0f)
                }
            }
            // The three most telling, as they sit from left to right; the others follow (they join when the colors move on).
            val three = chosen.take(3).sortedBy { it.x }
            return (three + chosen.drop(3)).map { it.rgb }
        }
    }
}

/** [a] and [b] (linear sRGB) mixed by [f] in Oklab: a passing color never goes gray on the way. */
private fun mixOklab(a: FloatArray, b: FloatArray, f: Float, out: FloatArray) {
    when {
        f <= 0f -> a.copyInto(out)
        f >= 1f -> b.copyInto(out)
        else -> Oklab.toLinear(labOf(a).lerp(labOf(b), f)).copyInto(out)
    }
}
