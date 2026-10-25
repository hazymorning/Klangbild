package io.github.hazymorning.klangbild.math

/**
 * A small random source with a fixed seed (xorshift), so whatever it shapes comes out the same
 * on every start and every device: the dither of the scenes.
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
}
