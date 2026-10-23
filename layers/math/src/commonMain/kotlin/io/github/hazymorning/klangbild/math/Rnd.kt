package io.github.hazymorning.klangbild.math

import kotlin.math.min

/**
 * A small random source with a fixed seed (xorshift), so whatever it shapes comes out the same
 * on every start and every device: cut paper, grain, dither.
 */
class Rnd(seed: Int) {
    private var s = seed * -0x61c88647 or 1

    /** A number from 0 until 1. */
    fun next(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return (s ushr 8) / 16777216f
    }

    /** Roughly normal, mean 0, standard deviation 1 (a sum of uniforms). */
    fun normal(): Float = (next() + next() + next() + next() - 2f) * 1.732f

    /** A whole number from 0 until [n]. */
    fun int(n: Int): Int = min(n - 1, (next() * n).toInt())
}
