package io.github.hazymorning.klangbild.text

import kotlinx.datetime.TimeZone

/** The time zone [id] names ("Europe/Berlin"), with its rules wherever the tests run. */
expect fun testZone(id: String): TimeZone
