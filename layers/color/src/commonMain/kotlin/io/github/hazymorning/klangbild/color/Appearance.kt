package io.github.hazymorning.klangbild.color

/**
 * The background the stage is drawn on: a warm off-white (light) or black (dark). Automatic
 * follows the sun ([bySun]): light from sunrise to sunset.
 */
enum class Appearance {
    LIGHT,
    DARK,
    ;

    companion object {
        /** The sun's elevation (°) at sunrise and sunset, as Home Assistant's `sun.sun` defines them. */
        private const val HORIZON = -0.833

        /** How far past the horizon the sun must go to switch the background again (°): no flicker at dusk. */
        private const val HYSTERESIS = 0.5

        /** Light while the sun is up, dark from sunset to sunrise; [previous] holds it steady near the horizon. */
        fun bySun(elevation: Double, previous: Appearance? = null): Appearance = when {
            previous == DARK && elevation < HORIZON + HYSTERESIS -> DARK
            previous == LIGHT && elevation > HORIZON - HYSTERESIS -> LIGHT
            elevation >= HORIZON -> LIGHT
            else -> DARK
        }
    }
}
