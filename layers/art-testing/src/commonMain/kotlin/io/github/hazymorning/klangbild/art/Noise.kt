package io.github.hazymorning.klangbild.art

import kotlin.math.floor

/** Small deterministic value-noise toolkit for procedural artwork. */
class Noise(seed: Int) {
    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        var s = seed * 1103515245 + 12345
        for (i in 255 downTo 1) {
            s = s * 1103515245 + 12345
            val j = ((s ushr 16) and 0x7FFF) % (i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun hash(x: Int, y: Int): Float = perm[(perm[x and 255] + y) and 511] / 255f

    private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)

    /** Smooth value noise in 0..1. */
    fun value(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = fade(xf)
        val v = fade(yf)
        val a = hash(xi, yi)
        val b = hash(xi + 1, yi)
        val c = hash(xi, yi + 1)
        val d = hash(xi + 1, yi + 1)
        val ab = a + (b - a) * u
        val cd = c + (d - c) * u
        return ab + (cd - ab) * v
    }

    /** Fractal Brownian motion, 0..1. */
    fun fbm(x: Float, y: Float, octaves: Int = 5, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var amp = 0.5f
        var freq = 1f
        var sum = 0f
        var norm = 0f
        repeat(octaves) {
            sum += amp * value(x * freq, y * freq)
            norm += amp
            amp *= gain
            freq *= lacunarity
        }
        return sum / norm
    }

    /** 1D noise along x, convenient for ridgelines. */
    fun line(x: Float, row: Float = 0.5f, octaves: Int = 5): Float = fbm(x, row, octaves)
}
