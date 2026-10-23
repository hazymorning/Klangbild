package io.github.hazymorning.klangbild.sound

actual fun monotonicMicros(): Long = System.nanoTime() / 1_000
