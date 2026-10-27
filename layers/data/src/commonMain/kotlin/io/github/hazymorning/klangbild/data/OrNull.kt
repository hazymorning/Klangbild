package io.github.hazymorning.klangbild.data

import kotlinx.coroutines.CancellationException

/** Runs [block]; null when it throws anything but a cancellation. */
internal inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
