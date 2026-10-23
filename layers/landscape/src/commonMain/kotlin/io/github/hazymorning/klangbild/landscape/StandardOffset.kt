package io.github.hazymorning.klangbild.landscape

import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** [zone]'s offset from UTC at [at] without daylight saving time (seconds). */
internal expect fun standardOffsetSeconds(zone: TimeZone, at: Instant): Int
