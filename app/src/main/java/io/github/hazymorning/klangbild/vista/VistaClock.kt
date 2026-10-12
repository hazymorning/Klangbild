package io.github.hazymorning.klangbild.vista

import kotlin.math.abs

/**
 * The moment whose sky the landscape shows: the real one or, for measuring on the device
 * (the launch extra [io.github.hazymorning.klangbild.config.LaunchExtras.LANDSCAPE] set to
 * `timelapse`), a whole day passing in [TIMELAPSE_DAY_MS]. Between the two the picture glides;
 * the light never jumps.
 */
class VistaClock(private val now: () -> Long = System::currentTimeMillis) {

    /** A day in two minutes. */
    @Volatile
    var timelapse: Boolean = false
        private set

    // The glide from where the picture was to where it is going (sky time, ms).
    private var glideFrom = 0L
    private var glideStart = 0L
    private var glideMs = 0L
    private var anchorSky = 0L
    private var anchorWall = 0L

    /** Lets a day pass in two minutes ([on]) or goes back to the real moment, gliding there. */
    @Synchronized
    fun timelapse(on: Boolean) {
        if (on == timelapse) return
        val wall = now()
        val from = skyAt(wall)
        timelapse = on
        if (on) {
            // The time-lapse starts from what the picture shows: nothing to glide.
            anchorSky = from
            anchorWall = wall
            glideMs = 0L
            return
        }
        glideFrom = from
        glideStart = wall
        // Longer for a bigger turn of the clock, never abrupt, never long.
        glideMs = (1_200L + abs(wall - from) / 3_600_000L * 160L).coerceAtMost(3_200L)
    }

    /** The moment whose sky is shown now (epoch ms). */
    @Synchronized
    fun sky(): Long = skyAt(now())

    private fun skyAt(wall: Long): Long {
        if (timelapse) return anchorSky + ((wall - anchorWall).coerceAtLeast(0L) * (DAY_MS / TIMELAPSE_DAY_MS.toDouble())).toLong()
        if (glideMs > 0L && wall < glideStart + glideMs) {
            val t = ((wall - glideStart).toFloat() / glideMs).coerceIn(0f, 1f)
            val e = t * t * (3 - 2 * t)
            // While gliding the real time moves on: follow it.
            return glideFrom + ((wall - glideFrom) * e).toLong()
        }
        return wall
    }

    companion object {
        const val DAY_MS = 86_400_000L

        /** In the time-lapse a day passes in two minutes. */
        const val TIMELAPSE_DAY_MS = 120_000L
    }
}
