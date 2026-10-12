package io.github.hazymorning.klangbild.lights

import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.render.FieldScene
import io.github.hazymorning.klangbild.vista.PaperLook
import io.github.hazymorning.klangbild.vista.SheetId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where a light stands: left of the screen, behind its middle (above it), right of it. */
enum class LightSlot { LEFT, CENTER, RIGHT }

/** Where the lights' colors come from: what the screen shows. */
interface LightColors {
    /** The color (linear sRGB, 0..1) for the light at [slot] at the field's time [t] (seconds). */
    fun color(slot: LightSlot, t: Double, out: FloatArray)
}

/**
 * Three lights, three colors: each light shows a color of its own from the cover. These are the
 * cover's most telling colors, kept apart (a cover of one color gives that color and two
 * neighbors of it, a gray one three whites), placed as they sit in the cover: the color that
 * stands leftmost in the field goes to the left light, the rightmost to the right one, the third
 * to the middle. With motion on, the colors move on from light to light as the field moves,
 * slowly, each resting a while before it passes on.
 */
class CoverLightColors(field: FieldScene, private val moving: Boolean) : LightColors {
    /** The lights' colors (linear sRGB), the left light's first: three, or up to five when they move on. */
    val colors: List<FloatArray> = palette(field)

    override fun color(slot: LightSlot, t: Double, out: FloatArray) {
        val n = colors.size
        val i = slot.ordinal
        if (!moving || n < 2) {
            colors[i % n].copyInto(out)
            return
        }
        // One step along the lights every ROTATE_S of the field's time; most of it resting.
        val p = t / ROTATE_S + i
        val k = floor(p)
        val f = smoothstep(HOLD, 1f, (p - k).toFloat())
        val a = colors[Math.floorMod(k.toLong(), n.toLong()).toInt()]
        val b = colors[Math.floorMod(k.toLong() + 1, n.toLong()).toInt()]
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
        private const val NEIGHBOUR_HUE = 32f

        /** The cover's colors for the lights: the most telling ones, kept apart, ordered as they sit from left to right. */
        fun palette(field: FieldScene): List<FloatArray> {
            class C(val rgb: FloatArray, val lab: Lab, val x: Float, var weight: Float)
            val found = ArrayList<C>()
            fun add(rgb: FloatArray, x: Float, weight: Float) {
                val lab = Oklab.fromLinear(rgb[0].coerceAtLeast(0f), rgb[1].coerceAtLeast(0f), rgb[2].coerceAtLeast(0f))
                // Color counts for more than size: a small red patch tells more than a big gray.
                val telling = weight * (0.25f + lab.toLch().c * 6f) * (0.4f + lab.l)
                val same = found.firstOrNull { distance(it.lab, lab) < SAME }
                if (same != null) {
                    same.weight += telling
                    return
                }
                found += C(rgb.copyOf(), lab, x, telling)
            }
            for (b in field.blobs) add(b.light, b.homeX, b.alpha * b.radius * b.radius * if (b.glow) 0.6f else 1f)
            add(field.baseLight, 0.5f, 0.05f)
            val chosen = found.sortedByDescending { it.weight }.take(5).toMutableList()
            // Fewer than three: the strongest color's neighbors round the hue circle, or for a
            // gray cover whites a little warmer and cooler.
            if (chosen.size < 3) {
                val lead = chosen.firstOrNull()?.lab?.toLch() ?: Lch(0.7f, 0f, 0f)
                val extra = if (lead.c < LightColor.GREY_CHROMA) {
                    // Whites a breath warmer and cooler: just past what the light shows as gray.
                    listOf(Lch(lead.l, 0.018f, 70f), Lch(lead.l, 0.018f, 250f))
                } else {
                    listOf(Lch(lead.l, lead.c, lead.h - NEIGHBOUR_HUE), Lch(lead.l, lead.c, lead.h + NEIGHBOUR_HUE))
                }
                for ((n, lch) in extra.withIndex()) {
                    if (chosen.size >= 3) break
                    val lab = Oklab.gamutMap(lch.toLab())
                    chosen += C(Oklab.toLinear(lab), lab, if (n == 0) 0f else 1f, 0f)
                }
            }
            // The three most telling, as they sit from left to right; the others follow (they join when the colors move on).
            val three = chosen.take(3).sortedBy { it.x }
            return (three + chosen.drop(3)).map { it.rgb }
        }

        private fun distance(a: Lab, b: Lab): Float {
            val dl = a.l - b.l
            val da = a.a - b.a
            val db = a.b - b.b
            return kotlin.math.sqrt(dl * dl + da * da + db * db)
        }
    }
}

/**
 * The landscape's light, three colors from its own palette: in the middle its sky; on the
 * side away from the campfire the clearing's green (by night the moonlit wood); on the fire's
 * side the mountains' paper, and at night the fire's warm light. Where the sun stands low, its
 * side takes the glow of dawn or dusk. The lights stand round the window as the picture's
 * colors stand in it.
 */
class VistaLightColors(look: PaperLook, fireOnRight: Boolean) : LightColors {
    private val left: FloatArray
    private val center: FloatArray
    private val right: FloatArray

    init {
        val sky = look.inks(SheetId.SKY)
        val zenith = linear(sky.base)
        val middle = linear(sky.first)
        val horizon = linear(sky.second)
        center = FloatArray(3) { zenith[it] * 0.55f + middle[it] * 0.45f }
        // Day 1, night 0: the stars come out as the day goes.
        val day = 1f - look.stars.coerceIn(0f, 1f)
        val meadow = linear(look.inks(SheetId.MEADOW).base)
        val mountains = linear(look.inks(SheetId.RANGE_FAR).base)
        // At night the clearing's side takes the moonlit wood: a dark teal beside the sky's blue.
        val nightWood = linear(look.inks(SheetId.WOOD_NEAR).base)
        // Where the light comes from: shadows fall away from it.
        val fromRight = (-look.shadowSide).coerceIn(-1f, 1f)
        val glow = linear(look.horizonGlow)
        val fire = linear(look.fireGlow)
        fun side(sign: Float, fireHere: Boolean): FloatArray {
            val out = if (fireHere) mountains.copyOf() else meadow.copyOf()
            if (!fireHere) mix(out, nightWood, 1f - day)
            val towards = max(0f, sign * fromRight)
            mix(out, glow, min(0.9f, look.horizonGlowAlpha * 1.6f) * towards)
            if (fireHere) mix(out, fire, min(1f, 1.4f * look.fire) * (1f - day * 0.85f))
            return out
        }
        left = side(-1f, !fireOnRight)
        right = side(1f, fireOnRight)
    }

    override fun color(slot: LightSlot, t: Double, out: FloatArray) {
        when (slot) {
            LightSlot.LEFT -> left
            LightSlot.CENTER -> center
            LightSlot.RIGHT -> right
        }.copyInto(out)
    }

    private fun mix(out: FloatArray, c: FloatArray, a: Float) {
        if (a <= 0f) return
        for (k in 0..2) out[k] += (c[k] - out[k]) * a
    }

    private fun linear(argb: Int) = floatArrayOf(
        Oklab.srgbToLinear((argb shr 16) and 0xFF),
        Oklab.srgbToLinear((argb shr 8) and 0xFF),
        Oklab.srgbToLinear(argb and 0xFF),
    )
}

/** [a] and [b] (linear sRGB) mixed by [f] in Oklab: a passing color never goes gray on the way. */
private fun mixOklab(a: FloatArray, b: FloatArray, f: Float, out: FloatArray) {
    when {
        f <= 0f -> a.copyInto(out)
        f >= 1f -> b.copyInto(out)
        else -> {
            val x = Oklab.fromLinear(a[0].coerceAtLeast(0f), a[1].coerceAtLeast(0f), a[2].coerceAtLeast(0f))
            val y = Oklab.fromLinear(b[0].coerceAtLeast(0f), b[1].coerceAtLeast(0f), b[2].coerceAtLeast(0f))
            Oklab.toLinear(Lab(x.l + (y.l - x.l) * f, x.a + (y.a - x.a) * f, x.b + (y.b - x.b) * f)).copyInto(out)
        }
    }
}

private fun smoothstep(e0: Float, e1: Float, v: Float): Float {
    val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
