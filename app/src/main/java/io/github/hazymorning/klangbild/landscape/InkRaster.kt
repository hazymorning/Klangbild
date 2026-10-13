package io.github.hazymorning.klangbild.landscape

import kotlin.math.roundToInt

/**
 * A sheet while it is being cut: per pixel, the weights of its first and second ink (see
 * [Inks]) in red and green, the paper's light in blue and the coverage in alpha. They are packed
 * like the bitmap the screen draws (unpremultiplied ARGB, four bytes a pixel, so a 4K sheet
 * stays affordable). Pieces are laid on it like paper cut-outs: what comes later covers what is
 * below, anti-aliased by its coverage. Values given with [dither] get a triangular dither of
 * about one level, so slow ramps do not band (with a fixed seed, so the picture is the same
 * every time).
 */
class InkRaster(val width: Int, val height: Int, seed: Int = 1) {
    val pixels = IntArray(width * height)
    private var s = seed * -0x61c88647 or 1

    private fun rnd(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return (s ushr 8) / 16777216f
    }

    /** Lays weights ([wr], [wg], [wb]) over pixel ([x], [y]) with [coverage] 0..1. */
    fun over(x: Int, y: Int, wr: Float, wg: Float, wb: Float, coverage: Float, dither: Boolean = false) {
        if (coverage <= 0.001f || x < 0 || y < 0 || x >= width || y >= height) return
        val i = y * width + x
        val c = coverage.coerceAtMost(1f)
        val p = pixels[i]
        val a0 = (p ushr 24) / 255f
        val out = c + a0 * (1f - c)
        // Weights are color-like: they mix by coverage, like paint over paint.
        val k0 = a0 * (1f - c) / out
        val k1 = c / out
        val d = if (dither) 255f else 0f
        fun mix(old: Int, w: Float): Int {
            val v = old * k0 + w * 255f * k1 + if (d > 0f) rnd() - rnd() else 0f
            return v.roundToInt().coerceIn(0, 255)
        }
        val alpha = (out * 255f).roundToInt().coerceIn(0, 255)
        pixels[i] = (alpha shl 24) or (mix(p shr 16 and 0xFF, wr) shl 16) or (mix(p shr 8 and 0xFF, wg) shl 8) or mix(p and 0xFF, wb)
    }
}
