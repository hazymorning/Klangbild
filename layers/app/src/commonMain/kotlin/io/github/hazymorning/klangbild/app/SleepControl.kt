package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.domain.SleepTimer
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The sleep timer as the app runs it: set from the menu, it pauses the music once ([SleepTimer]),
 * and wakes the controller at the moment it expects to act.
 */
internal class SleepControl(private val host: ControllerHost) {
    private val timer = SleepTimer()
    private var job: Job? = null
    private var wake: Long? = null

    val choice: SleepChoice get() = timer.choice

    /** When the timer pauses the music (wall clock), while it is set. */
    val pausesAt: Long? get() = timer.pausesAt

    fun clear() = timer.clear()

    /** Steps the timer along the choices that fit what plays. */
    fun step(direction: Int, wrap: Boolean) {
        synchronized(host.lock) {
            val track = (host.ui.screen as? Screen.Stage)?.track
            val choices = timer.choices(track)
            val i = choices.indexOf(timer.choice).coerceAtLeast(0) + direction
            val next = if (wrap) choices[i.mod(choices.size)] else choices[i.coerceIn(0, choices.size - 1)]
            if (next != timer.choice) timer.set(next, host.now())
            host.recompute()
        }
    }

    /**
     * Looks at what plays at [t] and pauses the music when the timer says so; then makes sure
     * the controller wakes when the timer next expects to act.
     */
    fun evaluate(t: Long, track: TrackInfo?, position: Long?, speed: Float, playing: Boolean) {
        if (timer.evaluate(t, track, position, speed, playing)) host.scope.launch { host.source?.send(RemoteCommand.PAUSE) }
        schedule(timer.pausesAt)
    }

    /**
     * When the timer pauses, as shown: an estimate for an end shifts by a millisecond from tick
     * to tick (speed, rounding). Only a real change (over a second) is passed on, so the screen
     * is not redrawn for nothing.
     */
    fun shownPauseAt(shown: Long?): Long? {
        val at = timer.pausesAt
        return if (at != null && shown != null && abs(at - shown) <= STEADY_MS) shown else at
    }

    /** Stops waiting for the timer's moment (no screen shows the controller). */
    fun stop() {
        job?.cancel()
        job = null
        wake = null
    }

    /** Wakes the controller when the timer expects to act (a tick could be up to a second late). */
    private fun schedule(at: Long?) {
        val old = wake
        if (at == old || (at != null && old != null && abs(at - old) < RESCHEDULE_MS)) return
        job?.cancel()
        wake = at
        job = at?.let {
            host.scope.launch {
                // Never sooner than a moment from now: a time already past must not spin.
                delay((it - host.now()).coerceAtLeast(MIN_WAKE_MS))
                synchronized(host.lock) { wake = null }
                host.recompute()
            }
        }
    }

    companion object {
        /** The wake-up is never sooner than this from now. */
        const val MIN_WAKE_MS = 250L

        /** A new wake-up closer than this to the planned one keeps the planned one. */
        const val RESCHEDULE_MS = 250L

        /** A pause time that moves by no more than this is shown as it was. */
        const val STEADY_MS = 1_000L
    }
}
