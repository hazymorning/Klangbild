package io.github.hazymorning.klangbild.data

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with "full jitter" for reconnects: 1 s, 2 s, 4 s and so on, capped at
 * [maxMs]. Each delay is randomized between 50 % and 100 % so that a whole household of
 * devices does not hammer a restarting Home Assistant in lockstep.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long {
        val exp = baseMs * 2.0.pow(attempt.coerceAtMost(16)).toLong()
        val capped = min(exp, maxMs)
        attempt++
        return (capped / 2) + (random.nextDouble() * (capped / 2)).toLong()
    }

    fun reset() {
        attempt = 0
    }
}
