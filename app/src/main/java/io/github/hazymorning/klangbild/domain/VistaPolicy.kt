package io.github.hazymorning.klangbild.domain

/**
 * When the landscape takes the screen, and when the quiet clock keeps it:
 *
 * - Something is wrong (connecting, Home Assistant away, the speaker unavailable or
 *   unknown) or setup is open: never. Those screens have something to say.
 * - Chosen (up on the remote, the quick menu, a link, Home Assistant): at once, whatever
 *   plays, until it is left again ([leave]: up, back, the quick menu). The other keys go on
 *   doing what they always do.
 * - Paused, or nothing playing: after [pauseMs] without music and without a key pressed
 *   (0: never; then the paused cover gives way to the clock after three minutes, as before).
 * - Playing: after [playingMs] of music without a key pressed (0: never).
 *
 * Every key press starts the delays again. So a landscape that came by itself goes with any
 * key (which still does its work), and a chosen one stays. The rest clock is left for the moments
 * with nothing to show but the time: nothing plays and the landscape is off, or the delay has
 * not passed yet.
 */
class VistaPolicy {
    private var playingSince: Long? = null
    private var quietSince: Long? = null
    private var lastKey = 0L

    /** Chosen with the remote (or from outside): it stays until it is left. */
    var requested = false
        private set

    /**
     * As of the last [shows]: the landscape shows, or comes if nothing changes (its delay
     * runs). Its picture is baked ahead only then.
     */
    var onItsWay = false
        private set

    /** A key was pressed: the delays start again (a chosen landscape stays). */
    fun interaction(now: Long) {
        lastKey = now
    }

    /** Chosen with the remote, the quick menu or from outside. */
    fun request(show: Boolean) {
        requested = show
    }

    /** Left (up, back, the quick menu): the stage comes back, and the delays start again from now. */
    fun leave(now: Long) {
        requested = false
        lastKey = now
    }

    /** Whether the landscape shows instead of [screen] now. */
    fun shows(screen: Screen, now: Long, pauseMs: Long, playingMs: Long): Boolean {
        val playing = screen is Screen.Stage && !screen.linkDown &&
            (screen.status == PlayerStatus.PLAYING || screen.status == PlayerStatus.BUFFERING)
        playingSince = if (playing) playingSince ?: now else null
        val quiet = when (screen) {
            is Screen.Stage -> screen.status == PlayerStatus.PAUSED && !screen.linkDown
            is Screen.Rest -> screen.reason == RestReason.IDLE || screen.reason == RestReason.PAUSED
            is Screen.Setup -> false
        }
        quietSince = if (quiet) quietSince ?: now else null
        val trouble = screen is Screen.Setup || (screen is Screen.Rest && !quiet)
        onItsWay = !trouble && (requested || (playing && playingMs > 0) || (quiet && pauseMs > 0))
        if (trouble) return false
        if (requested) return true
        val since = when {
            playing -> playingSince?.takeIf { playingMs > 0 }?.let { it to playingMs }
            quiet -> quietSince?.takeIf { pauseMs > 0 }?.let { it to pauseMs }
            else -> null
        } ?: return false
        val (from, delay) = since
        return now - maxOf(from, lastKey) >= delay
    }
}
