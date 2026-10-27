package io.github.hazymorning.klangbild.data

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with equal jitter for reconnects: each delay is random between half and all
 * of a bound that doubles from [baseMs] (1 s, 2 s, 4 s, ...) up to [maxMs], so that devices that
 * lost the same server don't all retry at once.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long {
        // 2^16 times the base is far above any bound; the limit keeps the product from overflowing.
        val exp = baseMs * 2.0.pow(attempt.coerceAtMost(16)).toLong()
        val capped = min(exp, maxMs)
        attempt++
        return (capped / 2) + (random.nextDouble() * (capped / 2)).toLong()
    }

    fun reset() {
        attempt = 0
    }
}
