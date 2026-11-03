package io.github.hazymorning.klangbild.domain

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.abs
import kotlin.math.exp

/**
 * Local progress interpolation. Home Assistant only reports the position when something
 * happens (play, pause, seek, track change), so the position is extrapolated from a report
 * (the best of the latest, [PositionReports]): `position + (now − reportedAt) × speed`, with
 * the server/local clock skew removed.
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
 * Of the latest reports of where the track stands, the one that runs furthest ahead. A report
 * is often a little late: many players count whole seconds, and a report carries the time it
 * was passed on, not the time it was measured. So while the track plays on, the report that runs
 * furthest ahead is the closest one. Right after the track starts, resumes or jumps, a report can
 * also run early, while the speaker still loads: for [SETTLE_MS] the latest report counts, and
 * those reports are left out after. Reports of the last [WINDOW_MS] count. A pause, another track
 * or another speed starts afresh, and so does a report more than [SLACK_MS] off.
 */
class PositionReports {
    /** The track at [positionMs] at [atMs] on the server's clock, first seen at [seenMs] on the local one. */
    private class Report(val positionMs: Long, val atMs: Long, val seenMs: Long) {
        /** How far ahead the report runs at [speed]: of two reports, the one with the larger lead is ahead. */
        fun lead(speed: Double): Double = positionMs - atMs * speed
    }

    private var track: String? = null
    private var speed = 1f

    /** When the track started, resumed or jumped, on the local clock. */
    private var since = 0L
    private val reports = ArrayDeque<Report>()

    /**
     * [p] at the best of the latest reports of its track. [nowMs] is the local clock, [skewMs] the
     * local clock minus the server's, and [receivedAtMs] the local time [p] arrived, which counts
     * for a report without a time of its own.
     */
    fun best(p: PlayerSnapshot, nowMs: Long, skewMs: Long, receivedAtMs: Long): PlayerSnapshot {
        val position = p.positionMs
        val key = p.track?.key
        if (p.status != PlayerStatus.PLAYING || position == null || key == null) {
            clear()
            return p
        }
        if (key != track || p.speed != speed) {
            clear()
            track = key
            speed = p.speed
            since = nowMs
        }
        val at = p.positionAtEpochMs ?: (receivedAtMs - skewMs)
        val s = speed.toDouble()
        while (reports.isNotEmpty() && nowMs - reports.first().seenMs > WINDOW_MS) reports.removeFirst()
        if (reports.none { it.positionMs == position && it.atMs == at }) {
            val report = Report(position, at, nowMs)
            val counted = counted(s)
            if (counted != null && abs(report.lead(s) - counted.lead(s)) > SLACK_MS) {
                // The track jumped (or stalled): it settles anew.
                reports.clear()
                since = nowMs
            }
            reports.addLast(report)
        }
        val best = checkNotNull(counted(s))
        return if (best.positionMs == position && best.atMs == at) p else p.copy(positionMs = best.positionMs, positionAtEpochMs = best.atMs)
    }

    /** The report that counts: the latest while the track settles, then the one furthest ahead of those after. */
    private fun counted(speed: Double): Report? =
        reports.filter { it.seenMs - since >= SETTLE_MS }.maxByOrNull { it.lead(speed) } ?: reports.lastOrNull()

    /** Forgets every report, as for another speaker. */
    fun clear() {
        track = null
        reports.clear()
    }

    private companion object {
        /** How long after a start, a resume or a jump a report can still run early. */
        const val SETTLE_MS = 10_000L

        /** Reports this old no longer count, so the position recovers soon from a stall. */
        const val WINDOW_MS = 30_000L

        /** A report further than this from the one that counts means the track jumped or stalled. */
        const val SLACK_MS = 1_500L
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
        // Another track: what was shown belonged to the one before, and its next report must not
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
        // A track not reported last is shown as it is, and leaves the smoothing alone.
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
