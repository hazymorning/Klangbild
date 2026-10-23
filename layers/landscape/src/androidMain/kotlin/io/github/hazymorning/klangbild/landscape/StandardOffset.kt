package io.github.hazymorning.klangbild.landscape

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import kotlin.time.Instant
import kotlin.time.toJavaInstant

internal actual fun standardOffsetSeconds(zone: TimeZone, at: Instant): Int =
    zone.toJavaZoneId().rules.getStandardOffset(at.toJavaInstant()).totalSeconds
