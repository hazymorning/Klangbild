package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.data.Log

/** The log of the tests' hosts, which keeps nothing. */
object QuietLog : Log {
    override fun info(message: String) = Unit

    override fun warn(message: String) = Unit
}
