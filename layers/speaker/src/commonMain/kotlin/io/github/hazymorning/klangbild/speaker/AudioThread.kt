package io.github.hazymorning.klangbild.speaker

/**
 * A thread of its own for the speaker's audio, as the platform gives one: its writes to the
 * output block it, and nothing else runs on it.
 */
fun interface AudioThread {
    /** Starts a thread that runs [loop] until it returns. */
    fun start(loop: suspend () -> Unit)
}
