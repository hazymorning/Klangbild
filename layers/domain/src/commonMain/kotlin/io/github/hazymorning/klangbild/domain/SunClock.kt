package io.github.hazymorning.klangbild.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Instant

/**
 * The sun's elevation from the local clock, for when Home Assistant has no `sun.sun`: a smooth
 * day curve peaking at 13:00, up from about 7:40 to 18:20. Enough to choose the light or dark
 * background and to dim the lights behind the TV.
 */
object SunClock {
    /** The elevation (°) at [hour]:[minute]. */
    fun elevation(hour: Int, minute: Int): Double {
        val t = hour + minute / 60f
        return 50.0 * cos((t - 13.0) / 24.0 * 2.0 * PI) - 8.0
    }

    /** The elevation (°) at [epochMs], by the local clock. */
    fun elevationAt(epochMs: Long): Double =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).let { elevation(it.hour, it.minute) }
}
