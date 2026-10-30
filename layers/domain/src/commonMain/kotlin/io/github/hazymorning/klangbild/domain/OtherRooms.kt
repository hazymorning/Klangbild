package io.github.hazymorning.klangbild.domain

/**
 * Whether Klangbild shows music from another room instead of the speaker chosen on the setup
 * page (this room's). The chosen speaker shows whenever it plays, at once. A speaker that plays
 * keeps the screen: music never gives way to other music. One that has gone quiet gives it up,
 * after [pauseMs] when it is paused (so OK plays it on, as long as the stage would stay) or after
 * [backMs] when it has stopped (as between two tracks). Then the other room's speaker that started
 * playing last shows, or, while none plays, the chosen one again.
 */
class OtherRooms(private val backMs: Long = SpeakerSwitch.BACK_MS, private val pauseMs: Long = PAUSE_MS) {
    /** The other room's speaker that shows; null while the chosen one does. */
    var shown: String? = null
        private set

    /** Since when each other room's speaker plays, for the one that started last. */
    private val playingSince = HashMap<String, Long>()

    private val chosenQuiet = Quiet()
    private val shownQuiet = Quiet()

    /** While the speaker shown has gone quiet, when the screen changes unless it plays again. */
    var changeAt: Long? = null
        private set

    /**
     * Decides again from the [chosen] speaker and the [others] (by entity, in no order; null for
     * one Home Assistant doesn't have) at [t]; returns the other room's speaker that shows, or null
     * for the chosen one.
     */
    fun update(chosen: PlayerSnapshot?, others: Map<String, PlayerSnapshot?>, t: Long): String? {
        playingSince.keys.retainAll { others[it].plays() }
        for ((id, p) in others) if (p.plays() && id !in playingSince) playingSince[id] = t
        changeAt = null
        val chosenGivesUpAt = chosenQuiet.givesUpAt(chosen, t)
        // The chosen speaker shows whenever it plays, and so it does once the speaker shown is taken off the list.
        if (chosenGivesUpAt == null || shown?.let { it !in others } == true) show(null)
        val givesUpAt = shown.let { if (it == null) chosenGivesUpAt else shownQuiet.givesUpAt(others[it], t) } ?: return shown
        val next = playingSince.entries.filter { it.key != shown }.maxByOrNull { it.value }?.key
        return when {
            next == null && shown == null -> null
            t >= givesUpAt -> show(next)
            else -> shown.also { changeAt = givesUpAt }
        }
    }

    /** No other rooms (none chosen, or not shown): the chosen speaker shows at once. */
    fun reset() {
        show(null)
        playingSince.clear()
        chosenQuiet.clear()
        changeAt = null
    }

    private fun show(id: String?): String? {
        if (id != shown) {
            shown = id
            shownQuiet.clear()
        }
        return id
    }

    private fun PlayerSnapshot?.plays(): Boolean =
        this != null && track != null && (status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING)

    /** Since when a speaker has been paused, or has stopped. */
    private inner class Quiet {
        private var pausedSince: Long? = null
        private var stoppedSince: Long? = null

        /** When the speaker that plays [p], seen at [t], gives the screen up; null while it plays. */
        fun givesUpAt(p: PlayerSnapshot?, t: Long): Long? = when {
            p.plays() -> null.also { clear() }
            p?.status == PlayerStatus.PAUSED && p.track != null -> {
                stoppedSince = null
                (pausedSince ?: t.also { pausedSince = it }) + pauseMs
            }
            else -> {
                pausedSince = null
                (stoppedSince ?: t.also { stoppedSince = it }) + backMs
            }
        }

        fun clear() {
            pausedSince = null
            stoppedSince = null
        }
    }

    companion object {
        /** As long as a paused stage stays before the rest screen takes its place ([Timing.pauseToRestMs]). */
        const val PAUSE_MS = 3 * 60_000L
    }
}
