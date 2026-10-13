package io.github.hazymorning.klangbild.domain

import java.util.Calendar
import kotlin.math.cos

/**
 * The sun's elevation from the local clock, for when Home Assistant has no `sun.sun`: a smooth
 * day curve peaking at 13:00, up from about 7:40 to 18:20. Enough to turn the wall and to dim
 * the lights behind the TV.
 */
object SunClock {
    /** The elevation (°) at [hour]:[minute]. */
    fun elevation(hour: Int, minute: Int): Double {
        val t = hour + minute / 60f
        return 50.0 * cos((t - 13.0) / 24.0 * 2.0 * Math.PI) - 8.0
    }

    /** The elevation (°) at [epochMs], by the local clock. */
    fun elevationAt(epochMs: Long): Double =
        Calendar.getInstance().apply { timeInMillis = epochMs }.let { elevation(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
}
