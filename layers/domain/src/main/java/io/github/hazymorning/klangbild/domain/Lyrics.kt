package io.github.hazymorning.klangbild.domain

/**
 * One line of a song's words, sung from [startMs] in the song's own time. Lines that start
 * together (two voices, a line and its echo) are one line, each on a row of its own in [text].
 */
data class LyricLine(val startMs: Long, val text: String)

/**
 * A song's synced words: its lines in the order they are sung, and the moments the singing rests
 * for a while ([breaks]: an instrumental passage, which the words mark with an empty line). Words
 * without times are none here: nothing could follow the song with them. Compared by identity: a
 * song's words are read once and then passed on as they are.
 */
class Lyrics(val lines: List<LyricLine>, val breaks: List<Long> = emptyList()) {
    init {
        require(lines.isNotEmpty()) { "Lyrics need a line" }
    }

    /**
     * Where the words stand at [positionMs]: the line sung last stands in the middle, lit while
     * it is sung. Before the first line the first one waits there unlit, and so does the last
     * line sung before a break.
     */
    fun placeAt(positionMs: Long): LyricPlace {
        val i = lines.indexOfLast { it.startMs <= positionMs }
        if (i < 0) return LyricPlace(0, lit = false)
        val start = lines[i].startMs
        return LyricPlace(i, lit = breaks.none { it in (start + 1)..positionMs })
    }
}

/** The line that stands in the middle of the words ([index]), and whether it is being sung. */
data class LyricPlace(val index: Int, val lit: Boolean)

/** What is known of the words of the song with the key [key]: synced [lyrics], or null for none. */
data class SongLyrics(val key: String, val lyrics: Lyrics?)

/**
 * Where the words stand as the song plays, a little early ([leadMs]) so that each line arrives
 * with the voice. It follows the position the progress bar shows, but a small step back (a
 * correction of the reported position, glided over by the bar) never moves it back: only a real
 * jump back does, more than [BACK_SLACK_MS] behind what it had reached.
 */
class LyricsCursor(private val lyrics: Lyrics, private val leadMs: Long = LEAD_MS) {
    /** The furthest moment seen since the place last changed (ms, lead included). */
    private var reached = Long.MIN_VALUE

    var place: LyricPlace = lyrics.placeAt(0)
        private set

    /** The place at [positionMs]; without a position, the place stays. */
    fun at(positionMs: Long?): LyricPlace {
        val p = (positionMs ?: return place) + leadMs
        if (p >= reached || p < reached - BACK_SLACK_MS) {
            place = lyrics.placeAt(p)
            reached = p
        }
        return place
    }

    companion object {
        /** A line moves into the middle this much before it is sung, so it lands as the voice comes in. */
        const val LEAD_MS = 300L

        /** A step back smaller than this is a correction of the position, not a jump. */
        const val BACK_SLACK_MS = 1_500L
    }
}

/**
 * Reads LRC, the common format of synced words: lines starting with one or more times
 * (`[01:23.45]`), as Music Assistant 2.10 and later gives them, tidied. It reads the raw form of
 * earlier versions too: tags (`[ar:…]`, `[offset:+250]`), several times on one line (a chorus),
 * times inside a line (`<01:23.45>`, a word's own), times with a colon before the fraction,
 * lines in any order. Words without times, or with one time for all, are not synced: none.
 */
object Lrc {
    /** One time: minutes, seconds and an optional fraction after a point or a colon. */
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** The times at the start of a line, then its words. */
    private val timed = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]\s*)+)(.*)$""")

    /** A tag of the whole text (`[ar:Artist]`, `[offset:+250]`) or a comment (`[#…]`): a name never starts with a digit. */
    private val tag = Regex("""^\[(?:([A-Za-z][A-Za-z0-9_ ]*):([^\]]*)|#[^\]]*)]$""")

    /** A word's own time inside a line, as enhanced LRC gives them. */
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    private val spaces = Regex("""\s+""")

    /** A part's name in brackets ("[Chorus]", "[Instrumental]"): not sung. */
    private val label = Regex("""^\[[^\]]*]$""")

    /** The synced words in [lrcLyrics], else in [lyrics] (earlier versions sometimes put them there); null for none. */
    fun of(lyrics: String?, lrcLyrics: String?): Lyrics? = parse(lrcLyrics) ?: parse(lyrics)

    /** The synced words in [text], or null when it holds none. */
    fun parse(text: String?): Lyrics? {
        if (text.isNullOrBlank()) return null
        var offset = 0L
        var lines = 0
        var timedLines = 0
        val entries = ArrayList<Pair<Long, String>>()
        for (raw in text.lineSequence().take(MAX_LINES)) {
            val line = raw.trim().trim('\uFEFF')
            if (line.isEmpty()) continue
            val t = tag.matchEntire(line)
            if (t != null) {
                if (t.groupValues[1].trim().equals("offset", ignoreCase = true)) {
                    t.groupValues[2].trim().removePrefix("+").toLongOrNull()?.let { offset = it }
                }
                continue
            }
            val m = timed.matchEntire(line)
            if (m == null) {
                // Words without a time: they cannot follow the song.
                lines++
                continue
            }
            // A time without words marks a rest.
            val words = clean(m.groupValues[2])
            if (words.isNotEmpty()) {
                lines++
                timedLines++
            }
            stamp.findAll(m.groupValues[1]).forEach { entries += msOf(it) to words }
        }
        // Plain words that mention a time here and there are not synced.
        if (timedLines < 2 || timedLines * 2 < lines) return null
        // A positive offset brings the words sooner (the LRC convention).
        val shifted = entries.map { (ms, words) -> (ms - offset).coerceAtLeast(0) to words }
        val sung = shifted.filter { it.second.isNotEmpty() }
        // One time for all, or for most: no real times.
        val commonest = sung.groupingBy { it.first }.eachCount().maxOf { it.value }
        if (commonest * 2 > sung.size) return null
        return build(shifted.sortedBy { it.first })
    }

    /**
     * The lines from [entries] in time order: those starting within [TOGETHER_MS] of each other
     * are one; symbols alone (a note, dots) and a label in brackets ("[Chorus]") count as rests.
     * A rest counts only after a line, and only when the singing pauses for at least
     * [MIN_BREAK_MS]; rests in a row are one.
     */
    private fun build(entries: List<Pair<Long, String>>): Lyrics? {
        class Group(val start: Long, val texts: MutableList<String>)
        val groups = ArrayList<Group>()
        for ((ms, words) in entries) {
            val last = groups.lastOrNull()
            if (last != null && ms - last.start <= TOGETHER_MS) {
                if (words.isNotEmpty() && words !in last.texts) last.texts += words
            } else {
                groups += Group(ms, if (words.isEmpty()) ArrayList() else arrayListOf(words))
            }
        }
        val lines = ArrayList<LyricLine>()
        val rests = ArrayList<Long>()
        for (g in groups) {
            val words = g.texts.filter { w -> w.any { it.isLetterOrDigit() } && !label.matches(w) }
            if (words.isEmpty()) {
                if (lines.isNotEmpty() && (rests.isEmpty() || rests.last() < lines.last().startMs)) rests += g.start
            } else {
                lines += LyricLine(g.start, words.joinToString("\n"))
            }
        }
        if (lines.isEmpty()) return null
        // A rest is a break only if the next line is a while away (or there is none: the song's end).
        val breaks = rests.filter { rest ->
            val next = lines.firstOrNull { it.startMs > rest }?.startMs
            next == null || next - rest >= MIN_BREAK_MS
        }
        return Lyrics(lines, breaks)
    }

    /** A line's words: without words' own times, spaces evened out, dashes and abbreviations set as on the stage. */
    private fun clean(words: String): String =
        TrackText.typeset(words.replace(wordStamp, "").replace(spaces, " ").trim().take(MAX_CHARS))

    private fun msOf(m: MatchResult): Long {
        val (minutes, seconds, fraction) = m.destructured
        val ms = when (fraction.length) {
            0 -> 0
            1 -> fraction.toInt() * 100
            2 -> fraction.toInt() * 10
            else -> fraction.toInt()
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + ms
    }

    /** Lines starting this close together are sung together. */
    const val TOGETHER_MS = 150L

    /** A rest shorter than this is a breath, not a break: the line stays lit. */
    const val MIN_BREAK_MS = 4_000L

    /** More lines than any song has; a longer text is cut, never trusted to be small. */
    private const val MAX_LINES = 2_000

    /** Longer than any line worth singing. */
    private const val MAX_CHARS = 400
}
