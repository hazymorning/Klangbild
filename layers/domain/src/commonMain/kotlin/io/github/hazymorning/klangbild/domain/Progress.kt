package io.github.hazymorning.klangbild.domain

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.abs
import kotlin.math.exp

/**
 * Local progress interpolation. Home Assistant only reports the position when something
 * happens (play, pause, seek, track change), so the position is extrapolated from the last
 * report: `position + (now − reportedAt) × speed`, with the server/local clock skew removed.
 * A book at 1.5× runs through its own time half as fast again.
 */
object Progress {
    /**
     * @param nowMs local wall clock
     * @param skewMs local minus server clock (see ClockSkew)
     * @param receivedAtMs local time the snapshot arrived, used if the server sent no timestamp
     */
    fun positionAt(snapshot: PlayerSnapshot, nowMs: Long, skewMs: Long, receivedAtMs: Long): Long? {
        val pos = snapshot.positionMs ?: return null
        val duration = snapshot.track?.durationMs
        val extrapolated = if (snapshot.status == PlayerStatus.PLAYING) {
            val reportedLocal = snapshot.positionAtEpochMs?.let { it + skewMs } ?: receivedAtMs
            pos + ((nowMs - reportedLocal).coerceAtLeast(0) * snapshot.speed.toDouble()).toLong()
        } else {
            pos
        }
        return if (duration != null && duration > 0) extrapolated.coerceIn(0, duration) else extrapolated.coerceAtLeast(0)
    }
}

/**
 * Glides over small corrections instead of jumping: when a new report differs from what
 * is on screen by less than [GLIDE_LIMIT_MS], the difference fades out with time constant
 * [TAU_MS]. Real seeks (bigger jumps) and track changes are shown immediately.
 */
class SmoothedPosition : SynchronizedObject() {
    private var correction = 0.0
    private var correctionAt = 0L
    private var lastShown: Long? = null
    private var lastKey: String? = null

    /** Call whenever a new snapshot arrives, with the raw position for "now". */
    fun onReport(trackKey: String?, rawNowMs: Long?, nowMs: Long): Unit = synchronized(this) {
        // Another song: what was shown belonged to the one before, and its next report must not
        // glide from there.
        if (trackKey != lastKey) lastShown = null
        val shown = lastShown
        if (rawNowMs == null || shown == null) {
            correction = 0.0
        } else {
            val diff = shown - rawNowMs
            correction = if (abs(diff) <= GLIDE_LIMIT_MS) diff.toDouble() else 0.0
        }
        correctionAt = nowMs
        lastKey = trackKey
    }

    /** Position to display now for [trackKey] (called from the draw phase). */
    fun display(trackKey: String?, rawNowMs: Long?, nowMs: Long): Long? = synchronized(this) {
        // A song not reported last is shown as it is, and leaves the smoothing alone.
        if (trackKey != lastKey) return rawNowMs
        if (rawNowMs == null) {
            lastShown = null
            return null
        }
        val decay = exp(-(nowMs - correctionAt).coerceAtLeast(0) / TAU_MS)
        val shown = (rawNowMs + correction * decay).toLong().coerceAtLeast(0)
        lastShown = shown
        return shown
    }

    private companion object {
        const val GLIDE_LIMIT_MS = 2_500L
        const val TAU_MS = 700.0
    }
}
