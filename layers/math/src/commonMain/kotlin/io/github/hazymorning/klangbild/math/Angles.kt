package io.github.hazymorning.klangbild.math

/** [degrees] in radians, to the last bit as the JVM's Math.toRadians gives it. */
fun radians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS

/** [radians] in degrees, to the last bit as the JVM's Math.toDegrees gives it. */
fun degrees(radians: Double): Double = radians * RADIANS_TO_DEGREES

private const val DEGREES_TO_RADIANS = 0.017453292519943295
private const val RADIANS_TO_DEGREES = 57.29577951308232
