package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.Chapters
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Progress
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * Jumps within what plays with the remote: in a book or an episode by presses, in a song by
 * holding left or right. A jump shows on the progress bar at once and holds there until Home
 * Assistant reports the new place; presses close together add up to one jump, sent once they
 * stop, and a hold is sent once the key is let go. Every change takes the controller's lock,
 * since the keys, the reports and the sends run on different threads.
 */
internal class Seeking(private val host: ControllerHost) {
    /** A jump that Home Assistant has not confirmed yet: the bar shows where it goes. The draw phase reads it without the lock. */
    @Volatile private var pending: Pending? = null
    private var job: Job? = null

    /** Where a hold began: the item's key and its place then. */
    private var holdFrom: Pair<String, Long>? = null

    /**
     * Jumps back ([direction] -1, [BACK_MS]) or ahead (+1, [AHEAD_MS]); presses in quick
     * succession add up to one jump.
     */
    fun skip(direction: Int) {
        synchronized(host.lock) {
            if (!host.settings.remoteControl) return
            val track = (host.ui.screen as? Screen.Stage)?.track ?: return
            val t = host.now()
            val from = position(t) ?: return
            seekTo(track, from + if (direction < 0) -BACK_MS else AHEAD_MS, t)
        }
    }

    /**
     * Held left or right: to the chapter's start (or the one before, right after a start) or to
     * the next chapter. Without chapters, or past the last one, to the item before or after.
     */
    fun jump(direction: Int) {
        synchronized(host.lock) {
            if (!host.settings.remoteControl) return
            val track = (host.ui.screen as? Screen.Stage)?.track ?: return
            val t = host.now()
            val at = position(t)?.let { Chapters.jump(track.chapters, it, direction) }
            if (at != null) seekTo(track, at, t) else host.send(if (direction < 0) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
        }
    }

    /**
     * Left or right held [heldMs] on a song: back ([direction] -1) or ahead (+1) from where it
     * played when the hold began, the farther the longer it is held ([held]). Sent once the key
     * is let go ([letGo]).
     */
    fun hold(direction: Int, heldMs: Long) {
        synchronized(host.lock) {
            if (!host.settings.remoteControl) return
            val track = (host.ui.screen as? Screen.Stage)?.track ?: return
            val t = host.now()
            val from = holdFrom?.takeIf { it.first == track.key }?.second ?: (position(t) ?: return).also { holdFrom = track.key to it }
            seekTo(track, from + direction * held(heldMs), t, HOLD_WAIT_MS)
        }
    }

    /** The key held is let go: the place it reached is sent now. */
    fun letGo() {
        synchronized(host.lock) {
            holdFrom = null
            val o = pending?.takeIf { it.sentAt == null } ?: return
            send(o, 0)
        }
    }

    /**
     * Where the item plays now in its own time: a jump not yet confirmed for the item [s]
     * reports, else the position it reports.
     */
    fun position(t: Long, s: SourceState = host.ui.source): Long? {
        pending?.takeIf { it.key == s.player?.track?.key }?.let { return it.at(t) }
        return s.player?.let { Progress.positionAt(it, t, s.skewMs, s.receivedAtMs) }
    }

    /**
     * A new report from Home Assistant at [t] with the item at [raw]: it confirms a jump that was
     * sent (or ends it, when another item plays). Returns the position to show. Called under the lock.
     */
    fun onReport(st: SourceState, raw: Long?, t: Long): Long? {
        pending?.let { o ->
            val confirmed = o.sentAt != null && raw != null && abs(raw - o.at(t)) < MATCH_MS
            if (st.player?.track?.key != o.key || confirmed) pending = null
        }
        return pending?.at(t) ?: raw
    }

    /** Forgets a jump on its way (the connection ends). */
    fun clear() {
        synchronized(host.lock) {
            job?.cancel()
            pending = null
        }
    }

    /** Shows and sends a jump to [target], unless the player can't seek. Called under the lock. */
    private fun seekTo(track: TrackInfo, target: Long, t: Long, wait: Long = SETTLE_MS) {
        val p = host.ui.source.player?.takeIf { it.abilities.seek } ?: return
        val end = track.durationMs?.let { (it - END_MARGIN_MS).coerceAtLeast(0) } ?: return
        val o = Pending(track.key, target.coerceIn(0, end), t, if (p.status == PlayerStatus.PLAYING) p.speed else 0f)
        pending = o
        // Presses close together make one jump: it is sent once they stop.
        send(o, wait)
        host.recompute()
    }

    /** Sends [o] after [wait] unless another jump replaces it meanwhile. Called under the lock. */
    private fun send(o: Pending, wait: Long) {
        job?.cancel()
        job = host.scope.launch {
            delay(wait)
            val sent = synchronized(host.lock) {
                // Another item plays meanwhile: the jump went with the one it was for.
                if (pending !== o || host.ui.source.player?.track?.key != o.key) return@launch
                o.copy(sentAt = host.now()).also { pending = it }
            }
            host.source?.seek(sent.at(host.now()))
            // Home Assistant answers within moments; if not, the reported position takes over again.
            delay(TRUST_MS)
            synchronized(host.lock) { if (pending === sent) pending = null }
        }
    }

    /** A jump not yet confirmed: from [target] at [startedAt] it runs on at [speed] (0 while paused). */
    private data class Pending(val key: String, val target: Long, val startedAt: Long, val speed: Float, val sentAt: Long? = null) {
        fun at(t: Long): Long = target + ((t - startedAt).coerceAtLeast(0) * speed).toLong()
    }

    companion object {
        /**
         * How far a hold of [heldMs] goes in a song (ms): ten seconds for each second held at
         * first, rising to [HOLD_FASTEST] seconds a second over [HOLD_RAMP_MS], so a moment's hold
         * finds a place nearby and a longer one crosses the song in a few seconds.
         */
        fun held(heldMs: Long): Long {
            val h = heldMs.coerceAtLeast(0) / 1000.0
            val ramp = HOLD_RAMP_MS / 1000.0
            val rise = (HOLD_FASTEST - HOLD_SLOWEST) / ramp
            val s = if (h <= ramp) HOLD_SLOWEST * h + rise * h * h / 2 else HOLD_SLOWEST * ramp + rise * ramp * ramp / 2 + HOLD_FASTEST * (h - ramp)
            return (s * 1000).toLong()
        }

        /** A hold moves this many seconds of the song for each second held at first, and at most this many. */
        const val HOLD_SLOWEST = 10.0
        const val HOLD_FASTEST = 40.0

        /** ...rising from the one to the other over this long (ms). */
        const val HOLD_RAMP_MS = 3_000L

        /** A hold whose key is never let go is sent after this long without a step (ms). */
        const val HOLD_WAIT_MS = 1_500L

        /** Left jumps back this far, right ahead this far (like most podcast apps). */
        const val BACK_MS = 15_000L
        const val AHEAD_MS = 30_000L

        /** A jump never lands closer to the end than this (Music Assistant refuses one past it). */
        const val END_MARGIN_MS = 2_000L

        /** Presses closer together than this add up to one jump. */
        const val SETTLE_MS = 500L

        /** How long a jump is shown before the reported position takes over again, confirmed or not. */
        const val TRUST_MS = 6_000L

        /** A reported position this close to the jump's confirms it. */
        const val MATCH_MS = 4_000L
    }
}
