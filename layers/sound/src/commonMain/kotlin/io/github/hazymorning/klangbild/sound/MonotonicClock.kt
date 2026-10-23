package io.github.hazymorning.klangbild.sound

/**
 * Now on the monotonic clock, in microseconds: the time of [SoundFrame.timeUs], the clock the
 * platform's audio output stamps its frames with.
 */
expect fun monotonicMicros(): Long
