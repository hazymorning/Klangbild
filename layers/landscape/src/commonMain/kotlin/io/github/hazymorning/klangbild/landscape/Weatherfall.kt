package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.math.hashUnit
import io.github.hazymorning.klangbild.math.smoothstep
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * What falls from the sky, at three depths (in front of the far range, of the clearing and of
 * the near banks), each moving with its layer: snowflakes of white paper that sail down and
 * sway, and rain as fine threads that slant with the wind. Every flake and every drop is a
 * number: where it is, how fast it falls and when it shows come from a hash of that number and
 * the clocks, never from a random draw. So nothing jumps, and as the snow or the rain gets
 * heavier, more of them simply fade in where they are. Near their ground they fade out: they
 * land on the meadow and never vanish in the air.
 */
object Weatherfall {
    /**
     * One depth of what falls: how many at full strength ([count]), their size (dp: a flake's
     * radius, a drop's length), how fast they fall (dp/s), how far a gale blows them sideways
     * (dp/s at wind 1), and between which heights they fall (dp).
     */
    class Band(
        val depth: Depth,
        val count: Int,
        val sizeMin: Float,
        val sizeMax: Float,
        val fallMin: Float,
        val fallMax: Float,
        val windDp: Float,
        val top: Float,
        val ground: Float,
        val seed: Int,
    )

    val SNOW = listOf(
        Band(Depth.FAR, 150, 0.75f, 1.2f, 8f, 13f, 26f, -24f, 372f, 401),
        Band(Depth.MID, 110, 1.2f, 1.9f, 15f, 22f, 38f, -24f, 476f, 402),
        Band(Depth.FRONT, 44, 1.9f, 2.9f, 26f, 36f, 56f, -30f, 566f, 403),
    )

    val RAIN = listOf(
        Band(Depth.FAR, 170, 8f, 13f, 300f, 380f, 140f, -40f, 372f, 411),
        Band(Depth.MID, 120, 13f, 19f, 420f, 520f, 190f, -40f, 476f, 412),
        Band(Depth.FRONT, 56, 19f, 28f, 560f, 700f, 260f, -50f, 580f, 413),
    )

    /** How many floats one item takes in [place]'s output: x, y (dp), size (dp), alpha, slant (dx per dy). */
    const val STRIDE = 5

    /**
     * Writes the items of [band] at the moment [t] (s) into [out], [STRIDE] floats each, for
     * a fall of [strength] (0..1); [drift] is how far the wind has blown so far (the integral of
     * the wind over time, so a gust never makes anything jump) and [wind] the wind now (for the
     * rain's slant). [snow]: flakes sway as they sail; drops fall straight along their slant.
     * Returns how many were written.
     */
    fun place(band: Band, t: Double, drift: Double, wind: Float, strength: Float, snow: Boolean, out: FloatArray): Int {
        if (strength <= 0.003f) return 0
        val left = -PaperMotif.OVERSCAN - 30f
        val wrap = PaperMotif.WIDTH + 2 * (PaperMotif.OVERSCAN + 30f)
        val span = band.ground - band.top
        var n = 0
        for (i in 0 until band.count) {
            if ((n + 1) * STRIDE > out.size) break
            val h1 = hashUnit(i, band.seed)
            val alpha = smoothstep(h1 - 0.05f, h1 + 0.05f, strength)
            if (alpha <= 0.01f) continue
            val h2 = hashUnit(i, band.seed + 17)
            val h3 = hashUnit(i, band.seed + 31)
            val h4 = hashUnit(i, band.seed + 47)
            val fall = band.fallMin + (band.fallMax - band.fallMin) * h2
            val y = band.top + floorMod(h3 * span + fall * t, span)
            // The wind blows them to the left (west to east, looking south); some catch more of it.
            val blown = band.windDp * (0.8 + 0.4 * h2) * drift
            var x = h4 * wrap - blown
            if (snow) x += (5f + 7f * h3) * sin(2 * PI * t / (3.2 + 2.6 * h1) + 6.2832 * h4)
            x = left + floorMod(x, wrap.toDouble())
            // Near their ground they land: they fade over the last stretch.
            val landing = 1f - smoothstep(band.ground - 26f, band.ground - 4f, y.toFloat())
            val a = alpha * landing
            if (a <= 0.01f) continue
            val o = n * STRIDE
            out[o] = x.toFloat()
            out[o + 1] = y.toFloat()
            out[o + 2] = band.sizeMin + (band.sizeMax - band.sizeMin) * h2
            out[o + 3] = a
            out[o + 4] = -band.windDp * (0.8f + 0.4f * h2) * wind / fall
            n++
        }
        return n
    }

    private fun floorMod(v: Double, m: Double): Double = v - floor(v / m) * m

    private fun floorMod(v: Float, m: Float): Double = floorMod(v.toDouble(), m.toDouble())

    private fun floorMod(v: Double, m: Float): Double = floorMod(v, m.toDouble())
}

/**
 * Mist lying in the valley: long bands of thin tissue drifting slowly in front of the far
 * range's feet and through the forest behind the clearing, never over the clearing itself,
 * where the fire and the tent stand. There are more of them, and denser, in fog, a few in rain,
 * and a breath of it on still mornings.
 */
object MistField {
    private class Lane(val depth: Depth, val y: Float, val speed: Float, val spacing: Float, val scale: Float, val stretch: Float, val seed: Int)

    private val lanes = listOf(
        Lane(Depth.FAR, 334f, 2.2f, 300f, 1.15f, 2.6f, 61),
        Lane(Depth.FAR, 358f, 1.7f, 340f, 1.0f, 2.4f, 67),
        Lane(Depth.MID, 380f, 2.8f, 360f, 0.85f, 2.4f, 71),
        Lane(Depth.MID, 398f, 3.3f, 420f, 0.7f, 2.8f, 73),
    )

    /** The bands of [depth] at the clouds' time [seconds] for a mist of [strength] (0..1). */
    fun places(seconds: Double, strength: Float, depth: Depth, out: MutableList<CloudPlace> = ArrayList()): List<CloudPlace> {
        out.clear()
        if (strength <= 0.003f) return out
        val left = -PaperMotif.OVERSCAN - 1_100f
        val right = PaperMotif.WIDTH + PaperMotif.OVERSCAN + 20f
        for (lane in lanes) {
            if (lane.depth != depth) continue
            val shift = seconds * lane.speed
            val first = floor((left + shift) / lane.spacing).toLong() - 1
            val last = kotlin.math.ceil((right + shift) / lane.spacing).toLong()
            for (k in first..last) {
                val h = (k * 0x9E3779B1L + lane.seed * 0x85EBCA77L).let { it xor (it ushr 15) }.toInt()
                val need = ((h ushr 4) and 0xFFF) / 4095f * 0.8f
                // Thin tissue: a veil even in thick fog.
                val alpha = smoothstep(need - 0.08f, need + 0.08f, strength) * (0.12f + 0.26f * strength)
                if (alpha <= 0.01f) continue
                val design = PaperBake.CLOUD_STRIPS + (h ushr 18).mod(PaperBake.CLOUD_HEAPS)
                val x = (k * lane.spacing - shift).toFloat()
                if (x > right || x < left) continue
                out += CloudPlace(design, x, lane.y + ((h and 0xF) - 7.5f), lane.scale, alpha, stretch = lane.stretch)
            }
        }
        return out
    }
}
