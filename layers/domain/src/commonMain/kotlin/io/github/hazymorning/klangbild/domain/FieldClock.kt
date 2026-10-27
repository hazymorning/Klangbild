package io.github.hazymorning.klangbild.domain

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * The color field's own time in seconds. It runs at the motion setting's speed while music
 * plays and stands still otherwise. The screen and the lights read the same clock, so
 * both move together, and neither a pause nor a new speed ever makes anything jump.
 *
 * Kept in double precision: after days of running a float would no longer resolve the
 * small steps between frames.
 */
class FieldClock(private val wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : SynchronizedObject() {
    private var anchorMs = wallClock()
    private var anchor = 0.0
    private var rate = 0f

    /** Runs at [rate] seconds per second from now on (0 holds it still). */
    fun run(rate: Float): Unit = synchronized(this) {
        if (rate == this.rate) return
        val t = wallClock()
        anchor = at(t)
        anchorMs = t
        this.rate = rate
    }

    /** The field's time at wall-clock [ms]. */
    fun at(ms: Long): Double = synchronized(this) { anchor + rate * (ms - anchorMs) / 1000.0 }

    /** The field's time now. */
    fun now(): Double = at(wallClock())
}
