package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.Chapters
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Progress
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Jumps within what plays (a book, an episode) with the remote. A jump shows on the progress
 * bar at once and holds there until Home Assistant reports the new place; presses close
 * together add up to one jump, sent once they stop.
 */
internal class Seeking(private val host: ControllerHost) {
    /** A jump that Home Assistant has not confirmed yet: the bar shows where it goes. */
    @Volatile private var pending: Pending? = null
    private var job: Job? = null

    /**
     * Jumps back ([direction] -1, [BACK_MS]) or ahead (+1, [AHEAD_MS]); presses in quick
     * succession add up to one jump.
     */
    fun skip(direction: Int) {
        if (!host.settings.remoteControl) return
        val track = (host.ui.screen as? Screen.Stage)?.track ?: return
        val t = host.now()
        val from = position(t) ?: return
        seekTo(track, from + if (direction < 0) -BACK_MS else AHEAD_MS, t)
    }

    /**
     * Held left or right: to the chapter's start (or the one before, right after a start) or to
     * the next chapter. Without chapters, or past the last one, to the item before or after.
     */
    fun jump(direction: Int) {
        if (!host.settings.remoteControl) return
        val track = (host.ui.screen as? Screen.Stage)?.track ?: return
        val t = host.now()
        val at = position(t)?.let { Chapters.jump(track.chapters, it, direction) }
        if (at != null) seekTo(track, at, t) else host.send(if (direction < 0) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
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
     * sent (or ends it, when another item plays). Returns the position to show.
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
        job?.cancel()
        pending = null
    }

    private fun seekTo(track: TrackInfo, target: Long, t: Long) {
        val end = track.durationMs?.let { (it - END_MARGIN_MS).coerceAtLeast(0) } ?: return
        val p = host.ui.source.player
        val o = Pending(track.key, target.coerceIn(0, end), t, if (p?.status == PlayerStatus.PLAYING) p.speed else 0f)
        pending = o
        job?.cancel()
        // Presses close together make one jump: it is sent once they stop.
        job = host.scope.launch {
            delay(SETTLE_MS)
            // Another item plays meanwhile: the jump went with the one it was for.
            if (pending !== o || host.ui.source.player?.track?.key != o.key) return@launch
            pending = o.copy(sentAt = host.now())
            host.source?.seek(o.at(host.now()))
            // Home Assistant answers within moments; if not, the reported position takes over again.
            delay(TRUST_MS)
            if (pending?.startedAt == o.startedAt) pending = null
        }
        host.recompute()
    }

    /** A jump not yet confirmed: from [target] at [startedAt] it runs on at [speed] (0 while paused). */
    private data class Pending(val key: String, val target: Long, val startedAt: Long, val speed: Float, val sentAt: Long? = null) {
        fun at(t: Long): Long = target + ((t - startedAt).coerceAtLeast(0) * speed).toLong()
    }

    companion object {
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
