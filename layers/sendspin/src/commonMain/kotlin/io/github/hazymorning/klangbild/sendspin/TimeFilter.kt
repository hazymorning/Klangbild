package io.github.hazymorning.klangbild.sendspin

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The server's clock as seen from here: Sendspin's time filter (messaging.md, "Time filter"), a
 * two-dimensional Kalman filter of the offset (server clock minus local clock) and its drift,
 * all in microseconds and double precision, with the spec's parameters. Each `server/time`
 * answer is one measurement; only the best of a burst is fed in.
 *
 * Not thread-safe: one thread feeds it and reads [snapshot]s, which other threads may use.
 */
class TimeFilter {
    private var offset = 0.0
    private var drift = 0.0
    private var pOo = Double.POSITIVE_INFINITY
    private var pOd = 0.0
    private var pDd = 0.0
    private var lastUpdate = 0L
    private var useDrift = false

    /** Measurements taken so far (it stops counting at the spec's `min_samples`). */
    var count = 0
        private set

    /** The standard deviation of the offset, in microseconds (infinite before the first measurement). */
    val errorUs: Double get() = sqrt(pOo)

    /** Forgets everything, as after the device slept (its monotonic clock stood still meanwhile). */
    fun reset() {
        offset = 0.0
        drift = 0.0
        pOo = Double.POSITIVE_INFINITY
        pOd = 0.0
        pDd = 0.0
        lastUpdate = 0L
        useDrift = false
        count = 0
    }

    /**
     * One `server/time` answer: [t1] sent here, [t2] received there, [t3] sent there, [t4]
     * received here, local times on the local clock and the others on the server's. Returns
     * whether it was taken.
     */
    fun update(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        val m = Measurement.of(t1, t2, t3, t4)
        return update(m.offset, m.maxError, t4)
    }

    /** One measurement: [measurement] the offset seen, [maxError] half the round trip, at local time [timeAdded]. */
    fun update(measurement: Long, maxError: Long, timeAdded: Long): Boolean {
        if (timeAdded <= lastUpdate || maxError <= 0) return false
        val dt = (timeAdded - lastUpdate).toDouble()
        lastUpdate = timeAdded
        val r = (MAX_ERROR_SCALE * maxError).let { it * it }
        val y0 = measurement.toDouble()
        when (count) {
            0 -> {
                offset = y0
                pOo = r
                drift = 0.0
                count = 1
                return true
            }
            1 -> {
                drift = (y0 - offset) / dt
                pDd = (pOo + r) / (dt * dt)
                offset = y0
                pOo = r
                count = 2
                return true
            }
        }
        val offsetP = offset + drift * dt
        var pOoP = pOo + 2 * pOd * dt + pDd * dt * dt + PROCESS_STD_DEV * PROCESS_STD_DEV * dt
        var pOdP = pOd + pDd * dt
        var pDdP = pDd + DRIFT_PROCESS_STD_DEV * DRIFT_PROCESS_STD_DEV * dt
        val y = y0 - offsetP
        if (count < MIN_SAMPLES) {
            count++
        } else if (abs(y) > ADAPTIVE_CUTOFF * maxError) {
            val f = FORGET_FACTOR * FORGET_FACTOR
            pOoP *= f
            pOdP *= f
            pDdP *= f
        }
        val s = pOoP + r
        val kO = pOoP / s
        val kD = pOdP / s
        offset = offsetP + kO * y
        drift += kD * y
        pOo = pOoP - kO * pOoP
        pOd = pOdP - kD * pOoP
        pDd = pDdP - kD * pOdP
        useDrift = drift * drift > DRIFT_SIGNIFICANCE * DRIFT_SIGNIFICANCE * pDd
        return true
    }

    /** The mapping as it stands, to be handed to other threads. */
    fun snapshot(): ClockMap = ClockMap(offset, if (useDrift) drift else 0.0, lastUpdate)

    /** One `server/time` exchange in the filter's terms. */
    data class Measurement(val offset: Long, val maxError: Long) {
        companion object {
            fun of(t1: Long, t2: Long, t3: Long, t4: Long) = Measurement(
                offset = ((t2 - t1) + (t3 - t4)) / 2,
                maxError = ((t4 - t1) - (t3 - t2)) / 2,
            )
        }
    }

    private companion object {
        // The spec's parameters, named as it names them.
        const val PROCESS_STD_DEV = 0.0
        const val DRIFT_PROCESS_STD_DEV = 1e-11
        const val FORGET_FACTOR = 2.0
        const val ADAPTIVE_CUTOFF = 3.0
        const val MIN_SAMPLES = 100
        const val DRIFT_SIGNIFICANCE = 2.0
        const val MAX_ERROR_SCALE = 0.5
    }
}

/**
 * A fixed mapping between the local clock and the server's (all in microseconds), as the time
 * filter had it: safe to share, cheap to use per audio block.
 */
class ClockMap(private val offset: Double, private val drift: Double, private val lastUpdate: Long) {
    fun serverTime(clientUs: Long): Long = clientUs + roundHalfAway(offset + drift * (clientUs - lastUpdate))

    fun clientTime(serverUs: Long): Long = roundHalfAway((serverUs - offset + drift * lastUpdate) / (1 + drift))

    /** The spec's `round`: to the nearest integer, halves away from zero. */
    private fun roundHalfAway(x: Double): Long = if (x >= 0) floor(x + 0.5).toLong() else -floor(-x + 0.5).toLong()
}
