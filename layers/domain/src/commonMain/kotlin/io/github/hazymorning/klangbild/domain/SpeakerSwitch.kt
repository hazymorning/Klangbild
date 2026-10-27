package io.github.hazymorning.klangbild.domain

/**
 * Whether Klangbild shows this TV's own speaker instead of the one chosen on the setup page.
 * Music that starts here takes the screen at once, also from a speaker that plays. Paused, it
 * stays, so OK plays it on. It gives the screen back once it has stopped (idle, off or gone)
 * for [backMs], or while it stands paused and the chosen speaker plays.
 */
class SpeakerSwitch(private val backMs: Long = BACK_MS) {
    /** This TV's own speaker shows. */
    var own = false
        private set

    private var stoppedSince: Long? = null

    /** While this TV's speaker has stopped and still shows, when the screen goes back to the chosen one. */
    val backAt: Long? get() = stoppedSince?.let { it + backMs }

    /** Decides again from this TV's speaker ([here]) and the chosen one at [t]; returns whether this TV's speaker shows. */
    fun update(here: PlayerSnapshot?, chosen: PlayerSnapshot?, t: Long): Boolean {
        val music = here?.track != null
        val status = here?.status
        when {
            music && (status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING) -> {
                own = true
                stoppedSince = null
            }
            !own -> stoppedSince = null
            music && status == PlayerStatus.PAUSED -> {
                stoppedSince = null
                if (chosen?.status == PlayerStatus.PLAYING) own = false
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
    }

    companion object {
        /** As between two tracks, the stage stays a moment before the chosen speaker comes back. */
        const val BACK_MS = 4_000L
    }
}
