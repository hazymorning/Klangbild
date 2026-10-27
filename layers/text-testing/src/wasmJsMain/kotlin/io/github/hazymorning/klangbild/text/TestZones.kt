package io.github.hazymorning.klangbild.text

import kotlinx.datetime.TimeZone

/** js-joda's time zone database: once imported, kotlinx-datetime knows every zone's rules. */
@OptIn(ExperimentalWasmJsInterop::class)
@JsModule("@js-joda/timezone")
private external object TimeZoneDatabase : JsAny

/** Written in [testZone] so the code refers to [TimeZoneDatabase], whose import loads the zone rules; nothing reads it. */
@OptIn(ExperimentalWasmJsInterop::class)
private var database: JsAny? = null

@OptIn(ExperimentalWasmJsInterop::class)
actual fun testZone(id: String): TimeZone {
    database = TimeZoneDatabase
    return TimeZone.of(id)
}
