package io.github.hazymorning.klangbild.domain

/** The sleep timer: what the music waits for before it pauses. */
enum class SleepChoice(val minutes: Int = 0) {
    OFF,
    MIN_15(15),
    MIN_30(30),
    MIN_45(45),
    MIN_60(60),

    /** The end of the chapter that plays (a whole item without chapters is one chapter). */
    CHAPTER_END,

    /** The end of what plays: the song, the episode, the book. */
    ITEM_END,
    ;

    val isEnd: Boolean get() = this == CHAPTER_END || this == ITEM_END
}

/**
 * The sleep timer: after a number of minutes, or when the chapter or what plays comes to its
 * end, the music pauses once, and then the timer is off again. It lives in the app, not in the
 * speaker, so it only works while Klangbild runs.
 *
 * Pure: the controller [evaluate]s it on every tick and wakes it at [wakeAt], the moment it
 * expects to act. An end counts only when playing reaches it. A jump across it (a seek, a
 * skip) moves the timer on to the end that follows.
 */
class SleepTimer {
    var choice: SleepChoice = SleepChoice.OFF
        private set
    private var setAtMs = 0L

    /** The item and the place in its time (ms) the timer waits for, and where playing was last seen. */
    private var waitKey: String? = null
    private var waitEndMs: Long? = null
    private var waitLeadMs = 0L
    private var lastPosMs: Long? = null
    private var lastSpeed = 1f
    private var lastLookMs = 0L

    /** When the timer expects to act (wall clock), for a precise wake-up; null while it cannot say. */
    var wakeAt: Long? = null
        private set

    fun set(choice: SleepChoice, nowMs: Long) {
        this.choice = choice
        setAtMs = nowMs
        waitKey = null
        waitEndMs = null
        lastPosMs = null
        wakeAt = if (choice.minutes > 0) nowMs + choice.minutes * 60_000L else null
    }

    fun clear() = set(SleepChoice.OFF, 0L)

    /** When the music pauses (wall clock): fixed for minutes, estimated for an end; null when off or unknown. */
    val pausesAt: Long? get() = if (choice == SleepChoice.OFF) null else wakeAt

    /**
     * Looks at what plays now ([positionMs] in [track]'s own time, at [speed]) and says whether
     * the music should pause this moment. When it says so the timer is off again.
     */
    fun evaluate(nowMs: Long, track: TrackInfo?, positionMs: Long?, speed: Float, playing: Boolean): Boolean {
        val c = choice
        if (c == SleepChoice.OFF) return false
        if (c.minutes > 0) {
            val due = nowMs >= setAtMs + c.minutes * 60_000L
            if (due) clear()
            return due && playing
        }
        if (track == null || positionMs == null) return false
        val s = speed.coerceIn(0.25f, 4f)
        // Reached by playing what it waited for: the end of the item or chapter, or a new item
        // right after the old one ended (when the item was over before the timer saw it).
        val reached = when {
            !playing -> false
            waitKey == track.key -> waitEndMs?.let { end ->
                val last = lastPosMs ?: positionMs
                // Played there: no further than the time since the last look allows (a jump goes further).
                val played = positionMs - last <= (nowMs - lastLookMs).coerceAtLeast(0) * s + PLAYED_SLACK_MS
                positionMs >= end - waitLeadMs && last < end && played
            } == true
            waitKey != null -> lastPosMs?.let { last -> waitEndMs?.let { end -> end - last <= NATURAL_END_MS * lastSpeed } } == true &&
                positionMs <= NATURAL_END_MS * s
            else -> false
        }
        if (reached) {
            clear()
            return true
        }
        // What to wait for from here on.
        val chapter = if (c == SleepChoice.CHAPTER_END) track.chapters.let { ch -> Chapters.at(ch, positionMs)?.let { ch[it] } } else null
        val last = chapter != null && chapter == track.chapters.lastOrNull()
        val end = chapter?.endMs?.takeIf { !last } ?: track.durationMs
        waitKey = track.key
        waitEndMs = end
        waitLeadMs = if (end == track.durationMs) ITEM_LEAD_MS else 0L
        lastPosMs = positionMs
        lastSpeed = s
        lastLookMs = nowMs
        // Already past it (a position stuck beyond a too short length): there is no wake-up to
        // wait for, and the next item tells that this one ran out.
        val ahead = end?.let { it - waitLeadMs - positionMs }
        wakeAt = if (playing && ahead != null && ahead > 0) nowMs + (ahead / s).toLong() else null
        return false
    }

    /** The choices that make sense for [track], from off to the latest. */
    fun choices(track: TrackInfo?): List<SleepChoice> = buildList {
        add(SleepChoice.OFF)
        add(SleepChoice.MIN_15)
        add(SleepChoice.MIN_30)
        add(SleepChoice.MIN_45)
        add(SleepChoice.MIN_60)
        if (track != null && track.chapters.isNotEmpty()) add(SleepChoice.CHAPTER_END)
        if (track != null && track.durationMs != null && track.kind != MediaKind.RADIO) add(SleepChoice.ITEM_END)
        if (choice !in this) add(choice)
    }

    companion object {
        /** What plays stops this long before its very end, so the queue never moves on to the next item first. */
        const val ITEM_LEAD_MS = 1_500L

        /** How far a reported position may run ahead of the time that passed (corrections, not jumps). */
        const val PLAYED_SLACK_MS = 2_500L

        /** A new item within this long of the old one's end means the old one ran out. */
        const val NATURAL_END_MS = 6_000L
    }
}
