package io.github.hazymorning.klangbild.math

/** 0 below [e0], 1 above [e1], and an S-shaped ramp between them (also when [e1] is the smaller). */
fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
