package io.github.hazymorning.klangbild.landscape

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * js-joda, which keeps kotlinx-datetime's time zones in the browser, has no standard offset:
 * here it is the lesser of the offsets at the start of January and of July, one of which is
 * always outside daylight saving time.
 */
internal actual fun standardOffsetSeconds(zone: TimeZone, at: Instant): Int {
    val year = at.toLocalDateTime(zone).year
    return listOf(1, 7).minOf { month -> zone.offsetAt(LocalDateTime(year, month, 1, 0, 0).toInstant(zone)).totalSeconds }
}
