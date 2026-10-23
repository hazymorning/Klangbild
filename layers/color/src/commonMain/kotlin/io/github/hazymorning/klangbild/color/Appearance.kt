package io.github.hazymorning.klangbild.color

/**
 * The wall the stage is drawn on: warm paper by day, black at night. Automatic follows the
 * sun: light from sunrise to sunset, the same rule Android and other TV systems use for their
 * own dark themes.
 */
enum class Appearance {
    LIGHT,
    DARK,
    ;

    companion object {
        /** The sun's elevation (°) at sunrise and sunset, as Home Assistant's `sun.sun` defines them. */
        const val HORIZON = -0.833

        /** How far past the horizon the sun must go to turn the wall again (°): no flicker at dusk. */
        const val HYSTERESIS = 0.5

        /** Light while the sun is up, dark from sunset to sunrise; [previous] holds it steady near the horizon. */
        fun bySun(elevation: Double, previous: Appearance? = null): Appearance = when {
            previous == DARK && elevation < HORIZON + HYSTERESIS -> DARK
            previous == LIGHT && elevation > HORIZON - HYSTERESIS -> LIGHT
            elevation >= HORIZON -> LIGHT
            else -> DARK
        }
    }
}
