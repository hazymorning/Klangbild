package io.github.hazymorning.klangbild.text

import kotlinx.datetime.TimeZone

actual fun testZone(id: String): TimeZone = TimeZone.of(id)
