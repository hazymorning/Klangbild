package io.github.hazymorning.klangbild.speaker

import io.github.hazymorning.klangbild.sound.monotonicMicros

/**
 * The local monotonic clock in microseconds, the clock of the output's timestamps. An interface of
 * its own rather than a function type, so reading it on the audio thread allocates nothing (a
 * function type returns a boxed number).
 */
fun interface MonoClock {
    fun nowUs(): Long

    companion object {
        val SYSTEM = MonoClock { monotonicMicros() }
    }
}
