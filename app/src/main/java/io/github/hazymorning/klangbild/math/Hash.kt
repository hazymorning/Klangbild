package io.github.hazymorning.klangbild.math

/**
 * A well-mixed hash of [k] and [seed] (splitmix64's finalizer): neighboring numbers give
 * unrelated values, so the items of a field can be placed by their number alone.
 */
fun hash64(k: Long, seed: Int): Long {
    var x = k * -0x61c8864680b583ebL + seed * 0x9E3779B97F4A7C15uL.toLong()
    x = (x xor (x ushr 33)) * -0xae502812aa7333L
    x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
    return x xor (x ushr 33)
}

/** [hash64] of an item's number [i] as a number from 0 to 1. */
fun hashUnit(i: Int, seed: Int): Float = ((hash64(i.toLong(), seed) ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
