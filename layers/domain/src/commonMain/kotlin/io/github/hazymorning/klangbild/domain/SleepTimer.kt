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
}

/**
 * The sleep timer: after a number of minutes, or when the chapter or what plays comes to its
 * end, the music pauses once, and then the timer is off again. It lives in the app, not in the
 * speaker, so it only works while Klangbild runs.
 *
 * It has no clock of its own: the controller calls [evaluate] on every tick and wakes it at
 * [pausesAt]. An end counts only when playing reaches it. A jump across it (a seek, a skip)
 * moves the timer on to the end that follows.
 */
class SleepTimer {
    var choice: SleepChoice = SleepChoice.OFF
        private set

    /** When the music pauses (wall clock): fixed for minutes, estimated for an end; null when off or unknown. */
    var pausesAt: Long? = null
        private set

    /** The item and the place in its time (ms) the timer waits for, and where playing was last seen. */
    private var waitKey: String? = null
    private var waitEndMs: Long? = null
    private var waitLeadMs = 0L
    private var lastPosMs: Long? = null
    private var lastSpeed = 1f
    private var lastLookMs = 0L

    fun set(choice: SleepChoice, nowMs: Long) {
        this.choice = choice
        waitKey = null
        waitEndMs = null
        lastPosMs = null
        pausesAt = if (choice.minutes > 0) nowMs + choice.minutes * MINUTE_MS else null
    }

    fun clear() = set(SleepChoice.OFF, 0L)

    /**
     * Looks at what plays now ([positionMs] in [track]'s own time, at [speed]) and says whether
     * the music should pause this moment. When it says so the timer is off again.
     */
    fun evaluate(nowMs: Long, track: TrackInfo?, positionMs: Long?, speed: Float, playing: Boolean): Boolean {
        if (choice == SleepChoice.OFF) return false
        if (choice.minutes > 0) {
            val due = nowMs >= pausesAt!!
            if (due) clear()
            return due && playing
        }
        if (track == null || positionMs == null) return false
        val s = speed.coerceIn(SPEED_RANGE)
        if (playing && endReached(nowMs, track, positionMs, s)) {
            clear()
            return true
        }
        waitForEnd(nowMs, track, positionMs, s, playing)
        return false
    }

    /**
     * Whether playing has reached the end the timer waits for, or a new item began right after
     * the old one ended (when the old one was over before the timer saw it).
     */
    private fun endReached(nowMs: Long, track: TrackInfo, positionMs: Long, speed: Float): Boolean {
        val end = waitEndMs ?: return false
        val last = lastPosMs ?: return false
        if (track.key != waitKey) return end - last <= NATURAL_END_MS * lastSpeed && positionMs <= NATURAL_END_MS * speed
        // Played there: no further than the time since the last look allows (a jump goes further).
        val played = positionMs - last <= (nowMs - lastLookMs).coerceAtLeast(0) * speed + PLAYED_SLACK_MS
        return positionMs >= end - waitLeadMs && last < end && played
    }

    /** Waits from here on for the end of the chapter or the item that plays, and says when it comes. */
    private fun waitForEnd(nowMs: Long, track: TrackInfo, positionMs: Long, speed: Float, playing: Boolean) {
        val chapters = track.chapters
        val chapter = if (choice == SleepChoice.CHAPTER_END) Chapters.at(chapters, positionMs)?.let { chapters[it] } else null
        // The last chapter ends with the item.
        val end = chapter?.takeIf { it != chapters.last() }?.endMs ?: track.durationMs
        waitKey = track.key
        waitEndMs = end
        waitLeadMs = if (end == track.durationMs) ITEM_LEAD_MS else 0L
        lastPosMs = positionMs
        lastSpeed = speed
        lastLookMs = nowMs
        // Already past it (a position stuck beyond a too short length): there is no wake-up to
        // wait for, and the next item tells that this one ran out.
        val ahead = end?.let { it - waitLeadMs - positionMs }
        pausesAt = if (playing && ahead != null && ahead > 0) nowMs + (ahead / speed).toLong() else null
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

        private const val MINUTE_MS = 60_000L

        /** The speeds the timer reckons with: at a reported speed of 0, no end would ever come. */
        private val SPEED_RANGE = 0.25f..4f

        /** How far a reported position may run ahead of the time that passed (corrections, not jumps). */
        private const val PLAYED_SLACK_MS = 2_500L

        /** A new item within this long of the old one's end means the old one ran out. */
        private const val NATURAL_END_MS = 6_000L
    }
}
