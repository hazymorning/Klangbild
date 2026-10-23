package io.github.hazymorning.klangbild.domain

/**
 * The color field's own time in seconds. It runs at the motion setting's speed while music
 * plays and stands still otherwise. The screen and the lights read the same clock, so
 * both move together, and neither a pause nor a new speed ever makes anything jump.
 *
 * Kept in double precision: after days of running a float would no longer resolve the
 * small steps between frames.
 */
class FieldClock(private val wallClock: () -> Long = System::currentTimeMillis) {
    private var anchorMs = wallClock()
    private var anchor = 0.0
    private var rate = 0f

    /** Runs at [rate] seconds per second from now on (0 holds it still). */
    @Synchronized
    fun run(rate: Float) {
        if (rate == this.rate) return
        val t = wallClock()
        anchor = at(t)
        anchorMs = t
        this.rate = rate
    }

    /** The field's time at wall-clock [ms]. */
    @Synchronized
    fun at(ms: Long): Double = anchor + rate * (ms - anchorMs) / 1000.0

    /** The field's time now. */
    fun now(): Double = at(wallClock())

    val running: Boolean @Synchronized get() = rate > 0f
}
