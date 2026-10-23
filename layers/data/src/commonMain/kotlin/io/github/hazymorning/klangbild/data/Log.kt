package io.github.hazymorning.klangbild.data

/** The app's log, as the platform keeps it: its connection steps, and never a token. */
interface Log {
    fun info(message: String)

    fun warn(message: String)
}
