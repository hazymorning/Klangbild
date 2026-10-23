package io.github.hazymorning.klangbild.domain

/** What plays: music (also when nothing says otherwise), a station, or spoken word. */
enum class MediaKind {
    MUSIC,
    RADIO,
    PODCAST,
    AUDIOBOOK,
    ;

    /** An episode or a book: listened to for its words, so the remote jumps instead of skipping. */
    val spoken: Boolean get() = this == PODCAST || this == AUDIOBOOK

    companion object {
        private val uri = Regex("""^[^:/]+://(podcast_episode|audiobook|radio)/""")

        /**
         * The kind from Music Assistant's URI (`library://audiobook/12`, `…://podcast_episode/…`);
         * Home Assistant's `media_content_type` says "music" for everything Music Assistant plays.
         */
        fun fromUri(contentId: String?): MediaKind? = contentId?.let { uri.find(it)?.groupValues?.get(1) }?.let(::fromType)

        /** Music Assistant's `media_type` ("podcast_episode", "audiobook", "radio", "track" and so on). */
        fun fromType(type: String?): MediaKind? = when (type) {
            "podcast_episode" -> PODCAST
            "audiobook" -> AUDIOBOOK
            "radio" -> RADIO
            "track" -> MUSIC
            else -> null
        }
    }
}

/** A chapter of a book or an episode, in the item's own time: from [startMs] to [endMs]. */
data class Chapter(val name: String?, val startMs: Long, val endMs: Long)

/**
 * Where the listener is in a book or an episode with chapters, as the stage shows it: the
 * chapter's name, its stretch of the item's time (the progress bar's range) and what follows.
 */
data class ChapterPlace(val index: Int, val count: Int, val label: String, val startMs: Long, val endMs: Long, val next: String?) {
    companion object {
        /**
         * The chapter at [positionMs] of [track], null without chapters or position; [numbered]
         * names a chapter by its number ("Chapter 7").
         */
        fun at(track: TrackInfo, positionMs: Long?, numbered: (Int) -> String): ChapterPlace? {
            val chapters = track.chapters
            val i = positionMs?.let { Chapters.at(chapters, it) } ?: return null
            val c = chapters[i]
            return ChapterPlace(
                index = i,
                count = chapters.size,
                label = Chapters.label(c, i, numbered),
                startMs = c.startMs,
                endMs = c.endMs,
                next = chapters.getOrNull(i + 1)?.let { Chapters.label(it, i + 1, numbered) },
            )
        }
    }
}

/** Chapters as Music Assistant gives them, and where in them the listener is. */
object Chapters {
    /** One chapter as read: its number, name and start, its end if known (seconds). */
    data class Raw(val position: Int, val name: String?, val start: Double, val end: Double?)

    /**
     * Sorted by start, each ending where it says, else where the next one starts, else at the
     * item's end ([durationMs]), as Music Assistant's own player does. Chapters that end before
     * they start, or start after the item's end, are left out; a single chapter is no chapter.
     */
    fun of(raw: List<Raw>, durationMs: Long?): List<Chapter> {
        val sorted = raw.filter { it.start.isFinite() && it.start >= 0 }.sortedWith(compareBy({ it.start }, { it.position }))
        val out = ArrayList<Chapter>(sorted.size)
        for ((i, c) in sorted.withIndex()) {
            val start = (c.start * 1000).toLong()
            val end = c.end?.takeIf { it.isFinite() && it > c.start }?.let { (it * 1000).toLong() }
                ?: sorted.getOrNull(i + 1)?.let { (it.start * 1000).toLong() }
                ?: durationMs
                ?: continue
            if (durationMs != null && start >= durationMs) continue
            if (end <= start) continue
            out += Chapter(c.name?.trim()?.takeIf { it.isNotEmpty() }, start, if (durationMs != null) end.coerceAtMost(durationMs) else end)
        }
        return if (out.size >= 2) out else emptyList()
    }

    /** The chapter at [positionMs] (its start ≤ position < its end), else null. */
    fun at(chapters: List<Chapter>, positionMs: Long): Int? {
        if (chapters.isEmpty()) return null
        val i = chapters.indexOfLast { it.startMs <= positionMs }
        if (i < 0) return null
        return i.takeIf { positionMs < chapters[i].endMs } ?: i.takeIf { it == chapters.size - 1 }
    }

    /**
     * Where a jump to the chapter before ([direction] −1) or after (+1) lands: back to the
     * current chapter's start unless it has only just begun (then the one before), ahead to
     * the next one's start. Null when there is none (before the first, after the last).
     */
    fun jump(chapters: List<Chapter>, positionMs: Long, direction: Int): Long? {
        val i = at(chapters, positionMs) ?: return null
        return if (direction < 0) {
            if (positionMs - chapters[i].startMs > RESTART_MS || i == 0) chapters[i].startMs else chapters[i - 1].startMs
        } else {
            chapters.getOrNull(i + 1)?.startMs
        }
    }

    /** Within this long of a chapter's start, "back" goes to the chapter before (like a CD player). */
    const val RESTART_MS = 3_000L

    private val generic = Regex("""^(?:(?:chapter|kapitel|track|part|teil|cd|disc|chap\.?|ch\.?|kap\.?)\s*)?0*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE)

    /**
     * How a chapter is named on the stage: its own name ("The Arrest"), or by its number with
     * [numbered] ("Chapter 7") for names that are only a number ("007", "Chapter 7", "Track 07")
     * or missing ([index] counts from 0).
     */
    fun label(chapter: Chapter, index: Int, numbered: (Int) -> String): String {
        val name = chapter.name ?: return numbered(index + 1)
        val number = generic.find(name)?.groupValues?.get(1)?.toIntOrNull()
        return if (number != null) numbered(number) else TrackText.typeset(Emoji.trimEdges(name)).ifEmpty { numbered(index + 1) }
    }
}
