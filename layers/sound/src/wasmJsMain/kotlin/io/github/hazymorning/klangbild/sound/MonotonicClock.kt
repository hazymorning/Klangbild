package io.github.hazymorning.klangbild.sound

actual fun monotonicMicros(): Long = (performanceNow() * 1_000).toLong()

/** The browser's monotonic clock, in milliseconds with a fraction. */
@OptIn(ExperimentalWasmJsInterop::class)
private fun performanceNow(): Double = js("performance.now()")
