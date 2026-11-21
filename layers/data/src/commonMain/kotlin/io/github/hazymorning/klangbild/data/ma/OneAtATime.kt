package io.github.hazymorning.klangbild.data.ma

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Asks Music Assistant about one thing at a time, the first of those wanted first. A call about
 * something no longer wanted is canceled, and its answer, should it come all the same, is dropped.
 */
internal class OneAtATime<T, A>(
    private val scope: CoroutineScope,
    /** The asker's lock: [want] is called under it, and every answer is kept under it. */
    private val lock: SynchronizedObject,
    /** Tells the things apart. */
    private val key: (T) -> String,
    /** The call about a thing. Every failure is an answer too: only a cancellation passes. */
    private val ask: suspend (T) -> A,
    /** Keeps an answer (under the lock); whether it is news. */
    private val keep: (T, A) -> Boolean,
    /** Called after an answer that is news. */
    private val changed: () -> Unit,
) {
    /** What is still to ask about, the first first. */
    private var wanted: List<T> = emptyList()

    /** The call that is out, and the key of what it is about. */
    private var job: Job? = null
    private var asking: String? = null

    /** What is wanted now, the first first (called under the lock). */
    fun want(things: List<T>) {
        wanted = things
        if (asking != null && things.none { key(it) == asking }) {
            job?.cancel()
            job = null
            asking = null
        }
        askNext()
    }

    /** Stops asking (the connection ends). */
    fun stop(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        asking = null
        wanted = emptyList()
    }

    /**
     * Asks about the first thing still wanted, unless a call is out (called under the lock). The
     * call is known as [job] before it starts, so even an answer that comes at once finds it.
     */
    private fun askNext() {
        if (job != null) return
        val thing = wanted.firstOrNull() ?: return
        val about = key(thing)
        asking = about
        val call = scope.launch(start = CoroutineStart.LAZY) {
            val answer = ask(thing)
            val me = coroutineContext.job
            val news = synchronized(lock) {
                // Canceled after the answer came (no longer wanted, or asking stopped): dropped.
                if (job !== me) return@launch
                job = null
                asking = null
                wanted = wanted.filter { key(it) != about }
                keep(thing, answer).also { askNext() }
            }
            if (news) changed()
        }
        job = call
        call.start()
    }
}
