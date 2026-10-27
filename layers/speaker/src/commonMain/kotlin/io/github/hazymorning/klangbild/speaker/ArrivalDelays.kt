package io.github.hazymorning.klangbild.speaker

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * How long audio takes from the server to here, for the `min_buffer_ms` the player reports
 * (player role, "Measuring timing parameters"). Each chunk's delay is when it arrived minus
 * when the server sent it; the worst of every ten seconds is kept for a minute, and the buffer
 * asked for is the second worst of those (one freak delay is let go) above [FLOOR_MS], in steps
 * of 50 ms. Allocates nothing.
 */
class ArrivalDelays {
    private val worst = LongArray(SLOTS)
    private val slotOf = LongArray(SLOTS) { -1 }
    private val lock = SynchronizedObject()

    /** A chunk sent at local time [sentUs] (the server's send time, converted) that arrived at [arrivalUs]. */
    fun add(sentUs: Long, arrivalUs: Long): Unit = synchronized(lock) {
        val delay = arrivalUs - sentUs
        if (delay < 0) return
        val slot = arrivalUs / SLOT_US
        val i = (slot % worst.size).toInt()
        if (slotOf[i] != slot) {
            slotOf[i] = slot
            worst[i] = 0
        }
        if (delay > worst[i]) worst[i] = delay
    }

    /** The buffer to ask for now ([nowUs]), in milliseconds. */
    fun minBufferMs(nowUs: Long): Int = synchronized(lock) {
        val current = nowUs / SLOT_US
        var first = 0L
        var second = 0L
        for (i in worst.indices) {
            if (slotOf[i] < 0 || current - slotOf[i] >= worst.size) continue
            val w = worst[i]
            if (w > first) {
                second = first
                first = w
            } else if (w > second) {
                second = w
            }
        }
        val ms = ((second + 999) / 1_000).toInt()
        val stepped = (ms + STEP_MS - 1) / STEP_MS * STEP_MS
        maxOf(FLOOR_MS, stepped).coerceAtMost(MAX_MS)
    }

    companion object {
        /** Wi-Fi in a home: never ask for less than this. */
        const val FLOOR_MS = 300
        private const val SLOT_US = 10_000_000L
        private const val SLOTS = 6
        private const val STEP_MS = 50
        private const val MAX_MS = 5_000
    }
}
