package io.github.hazymorning.klangbild.domain

/**
 * Some players report a skip in three steps: the new track at once, a moment later the old one
 * again (the player's own state catching up with its queue), then the new one for good. Shown
 * as reported, the screen jumps forth, back and forth.
 *
 * A return to a track left only moments ago ([WINDOW_MS]) is therefore held back until it has
 * lasted [HOLD_MS]. The flicker never reaches the screen, and a real return still does, a moment
 * later. A new track always passes at once, and so does a return asked for with the remote
 * ([expectReturn]). Nothing else is filtered, and nothing about any one player is assumed.
 */
class FlipGuard {
    /** The track last let through. */
    private var shown: String? = null

    /** Tracks left recently, and when. */
    private val left = ArrayDeque<Pair<String, Long>>()

    /** The return being held, and since when it has been reported. */
    private var held: Pair<String, Long>? = null
    private var returnWantedUntil = 0L

    /** When a held return will pass (if it is still reported then); null while nothing is held. */
    val passesAt: Long? get() = held?.let { it.second + HOLD_MS }

    /** "Previous" was sent: a return to a track just left is wanted, not a flicker. */
    fun expectReturn(t: Long) {
        returnWantedUntil = t + WINDOW_MS
    }

    /** Whether a report of [key] (null: nothing playing) at [t] may be shown now. */
    fun admit(key: String?, t: Long): Boolean {
        if (key == null || key == shown) {
            held = null
            return true
        }
        left.removeAll { t - it.second >= WINDOW_MS }
        val back = left.any { it.first == key }
        if (back && t >= returnWantedUntil) {
            val since = held?.takeIf { it.first == key }?.second ?: t
            held = key to since
            if (t - since < HOLD_MS) return false
        }
        held = null
        shown?.let { old ->
            left.removeAll { it.first == old }
            left.addLast(old to t)
            while (left.size > MAX_LEFT) left.removeFirst()
        }
        left.removeAll { it.first == key }
        shown = key
        return true
    }

    companion object {
        /** A return within this long after leaving a track may be a player catching up. */
        const val WINDOW_MS = 5_000L

        /** How long such a return has to last before it is shown. */
        const val HOLD_MS = 2_000L

        private const val MAX_LEFT = 4
    }
}
