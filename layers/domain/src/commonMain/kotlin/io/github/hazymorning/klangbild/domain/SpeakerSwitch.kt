package io.github.hazymorning.klangbild.domain

/**
 * Whether Klangbild shows this TV's own speaker instead of the one chosen on the setup page.
 * Music that starts here takes the screen at once, also from a speaker that plays. Paused, it
 * stays, so OK plays it on. It gives the screen back once it has stopped (idle, off or gone)
 * for [backMs], while it stands paused and the chosen speaker plays, or once it has stood paused
 * for [pauseMs] while music plays in another room ([OtherRooms]).
 */
class SpeakerSwitch(private val backMs: Long = BACK_MS, private val pauseMs: Long = OtherRooms.PAUSE_MS) {
    /** This TV's own speaker shows. */
    var own = false
        private set

    private var stoppedSince: Long? = null
    private var pausedSince: Long? = null

    /** While this TV's speaker has gone quiet and still shows, when the screen goes back. */
    val backAt: Long? get() = stoppedSince?.let { it + backMs } ?: pausedSince?.let { it + pauseMs }

    /**
     * Decides again from this TV's speaker ([here]), the chosen one and what another room plays
     * meanwhile ([elsewhere]) at [t]; returns whether this TV's speaker shows.
     */
    fun update(here: PlayerSnapshot?, chosen: PlayerSnapshot?, t: Long, elsewhere: PlayerSnapshot? = null): Boolean {
        val music = here?.track != null
        val status = here?.status
        if (!(music && status == PlayerStatus.PAUSED && own)) pausedSince = null
        when {
            music && (status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING) -> {
                own = true
                stoppedSince = null
            }
            !own -> stoppedSince = null
            music && status == PlayerStatus.PAUSED -> {
                stoppedSince = null
                // Paused while another room plays, it gives way once a paused stage would.
                pausedSince = if (elsewhere?.track != null && elsewhere.status == PlayerStatus.PLAYING) pausedSince ?: t else null
                val waited = pausedSince?.let { t - it >= pauseMs } == true
                if (chosen?.status == PlayerStatus.PLAYING || waited) {
                    own = false
                    pausedSince = null
                }
            }
            else -> {
                val since = stoppedSince ?: t.also { stoppedSince = it }
                if (t - since >= backMs) {
                    own = false
                    stoppedSince = null
                }
            }
        }
        return own
    }

    /** No own speaker (switched off, or not found yet): the chosen one shows. */
    fun reset() {
        own = false
        stoppedSince = null
        pausedSince = null
    }

    companion object {
        /** As between two tracks, the stage stays a moment before the chosen speaker comes back. */
        const val BACK_MS = 4_000L
    }
}
