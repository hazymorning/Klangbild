package io.github.hazymorning.klangbild.landscape

/**
 * The colors of one baked sheet of paper. A sheet stores, per pixel, how much of its [first]
 * and [second] ink lies over its [base] (weights w1 and w2, 0..1) and the light of the paper
 * itself (its grain, the shade it lies in, its lit edge) as a factor t around 1: red is
 * w1·t, green w2·t, blue t, all divided by [T_SCALE]; alpha is coverage. One color matrix
 * turns that into color every frame, so the light can change without the sheet being drawn
 * again: out = t·(base + w1·(first − base) + w2·(second − base)). Colors are sRGB ARGB ints;
 * the mix happens in sRGB, like ink.
 */
data class Inks(val base: Int, val first: Int, val second: Int) {

    /** The 4 × 5 color matrix (Android layout), the sheet's alpha kept. */
    fun matrix(out: FloatArray = FloatArray(20)): FloatArray {
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val b = (base shr shift and 0xFF) / 255f
            val f = (first shr shift and 0xFF) / 255f
            val s = (second shr shift and 0xFF) / 255f
            val row = c * 5
            out[row] = (f - b) * T_SCALE
            out[row + 1] = (s - b) * T_SCALE
            out[row + 2] = b * T_SCALE
            out[row + 3] = 0f
            out[row + 4] = 0f
        }
        out[15] = 0f
        out[16] = 0f
        out[17] = 0f
        out[18] = 1f
        out[19] = 0f
        return out
    }

    /** The color a pixel with weights [w1], [w2] and paper light [t] gets; the matrix computes the same. */
    fun mix(w1: Float, w2: Float, t: Float = 1f): Int {
        var out = 0xFF shl 24
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val b = (base shr shift and 0xFF).toFloat()
            val v = t * (b + w1 * ((first shr shift and 0xFF) - b) + w2 * ((second shr shift and 0xFF) - b))
            out = out or (v.toInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    companion object {
        /** The paper's light is stored up to this factor (blue = t / T_SCALE). */
        const val T_SCALE = 1.25f

        /** All three the same: a flat color (still with the paper's grain). */
        fun flat(argb: Int) = Inks(argb, argb, argb)
    }
}
