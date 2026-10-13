package io.github.hazymorning.klangbild.landscape

import kotlin.math.floor

/**
 * Smooth gradient noise (Perlin's improved noise, 1D and 2D) with a fixed permutation, so
 * the picture is the same on every start. Values lie roughly in −1..1. Used for the shapes of
 * the landscape when it is baked and for the slow, never repeating drifts of what moves.
 */
object Noise {
    private val perm = IntArray(512).also { p ->
        // A fixed shuffle (xorshift), independent of any platform random.
        val base = IntArray(256) { it }
        var s = 0x2545F491
        for (i in 255 downTo 1) {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            val j = Math.floorMod(s, i + 1)
            val t = base[i]
            base[i] = base[j]
            base[j] = t
        }
        for (i in 0 until 512) p[i] = base[i and 255]
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun grad1(hash: Int, x: Double): Double = if (hash and 1 == 0) x * ((hash shr 1 and 7) + 1) / 8.0 else -x * ((hash shr 1 and 7) + 1) / 8.0

    private fun grad2(hash: Int, x: Double, y: Double): Double = when (hash and 7) {
        0 -> x + y
        1 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x
        5 -> -x
        6 -> y
        else -> -y
    }

    /** 1D noise at [x] (a lattice step is 1), roughly −1..1. */
    fun at(x: Double): Double {
        val xi = floor(x).toInt()
        val xf = x - xi
        val a = perm[xi and 255]
        val b = perm[(xi + 1) and 255]
        val u = fade(xf)
        return 2.0 * (grad1(a, xf) + u * (grad1(b, xf - 1) - grad1(a, xf)))
    }

    /** 2D noise at ([x], [y]), roughly −1..1. */
    fun at(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = fade(xf)
        val v = fade(yf)
        val aa = perm[perm[xi and 255] + (yi and 255)]
        val ab = perm[perm[xi and 255] + ((yi + 1) and 255)]
        val ba = perm[perm[(xi + 1) and 255] + (yi and 255)]
        val bb = perm[perm[(xi + 1) and 255] + ((yi + 1) and 255)]
        val x1 = grad2(aa, xf, yf) + u * (grad2(ba, xf - 1, yf) - grad2(aa, xf, yf))
        val x2 = grad2(ab, xf, yf - 1) + u * (grad2(bb, xf - 1, yf - 1) - grad2(ab, xf, yf - 1))
        return 0.7 * (x1 + v * (x2 - x1))
    }

    /** Fractal sum of [octaves] 1D octaves, each half as strong and twice as fine. */
    fun fbm(x: Double, octaves: Int, gain: Double = 0.5, lacunarity: Double = 2.0): Double {
        var sum = 0.0
        var amp = 1.0
        var f = 1.0
        var norm = 0.0
        repeat(octaves) { i ->
            sum += amp * at(x * f + i * 17.31)
            norm += amp
            amp *= gain
            f *= lacunarity
        }
        return sum / norm
    }

    /** Fractal sum of [octaves] 2D octaves. */
    fun fbm(x: Double, y: Double, octaves: Int, gain: Double = 0.5, lacunarity: Double = 2.0): Double {
        var sum = 0.0
        var amp = 1.0
        var f = 1.0
        var norm = 0.0
        repeat(octaves) { i ->
            sum += amp * at(x * f + i * 17.31, y * f - i * 9.73)
            norm += amp
            amp *= gain
            f *= lacunarity
        }
        return sum / norm
    }

    /**
     * A slow wander for things that move by themselves: the sum of a few noises at
     * incommensurate rates, so nothing ever repeats. [t] in seconds, [period] the rough time
     * of one swing; [seed] separates independent wanders. Roughly −1..1.
     */
    fun wander(t: Double, period: Double, seed: Int): Double {
        val s = seed * 31.7
        val x = t / period
        return 0.62 * at(x + s) + 0.28 * at(x * 2.137 + s + 101.3) + 0.1 * at(x * 4.731 - s + 47.9)
    }
}
