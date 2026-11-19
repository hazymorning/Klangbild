package io.github.hazymorning.klangbild.domain

/**
 * What Music Assistant keeps about an artist, made fit for the screen: its Wikipedia provider
 * writes the opening of the artist's article in Music Assistant's language, and other sources
 * write HTML. The bio holds it in paragraphs of whole sentences, as plain text and without the
 * pronunciation an article opens with. No paragraph is empty.
 */
class ArtistBio(val paragraphs: List<List<String>>) {
    init {
        require(paragraphs.isNotEmpty() && paragraphs.all { it.isNotEmpty() }) { "A bio needs a sentence" }
    }

    companion object {
        /** [text] as a bio, or null when nothing of it is left to show. */
        fun of(text: String?): ArtistBio? {
            if (text.isNullOrBlank()) return null
            val paragraphs = plain(text).replace('\r', '\n').split('\n')
                .map { clean(it) }
                .filter { it.isNotEmpty() }
                .map { sentences(it) }
                .filter { it.isNotEmpty() }
            return if (paragraphs.isEmpty()) null else ArtistBio(paragraphs)
        }

        /**
         * [text] as plain text. Entities like `&amp;` become their characters first, so text that
         * was escaped twice loses its tags too. In HTML a line break in the source is a space, a
         * paragraph or `<br>` starts a new line, footnote marks and scripts go with what they
         * hold, and every other tag goes.
         */
        internal fun plain(text: String): String {
            var s = ENTITY.replace(text) { m -> character(m.groupValues[1]) ?: m.value }
            if (!TAG.containsMatchIn(s)) return s
            s = s.replace('\r', ' ').replace('\n', ' ')
            s = COMMENT.replace(s, "")
            s = HIDDEN.replace(s, "")
            s = BREAK.replace(s, "\n")
            return TAG.replace(s, "")
        }

        /** The character an entity stands for, by its name or number, or null for one that isn't known. */
        private fun character(entity: String): String? {
            val code = when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                else -> return ENTITIES[entity]
            } ?: return null
            return when (code) {
                9, 10, 13, in 0x20..0xD7FF, in 0xE000..0xFFFF -> code.toChar().toString()
                in 0x10000..0x10FFFF -> charArrayOf(
                    ((code - 0x10000) shr 10 or 0xD800).toChar(),
                    ((code - 0x10000) and 0x3FF or 0xDC00).toChar(),
                ).concatToString()
                else -> null
            }
        }

        /**
         * [paragraph] without what is only there to be pronounced: a group in parentheses that
         * holds a phonetic transcription or names its pronunciation, and any group in square
         * brackets (in plain text, only transcriptions use them). Spaces close up behind them.
         */
        internal fun clean(paragraph: String): String {
            var s = paragraph.replace(' ', ' ').replace('\t', ' ')
            // Inner groups first: a group that held one that is spoken is looked at again.
            repeat(3) { s = PARENTHESES.replace(s) { m -> if (spoken(m.value)) "" else m.value } }
            s = BRACKETS.replace(s, "")
            s = SPACES.replace(s, " ")
            s = SPACE_BEFORE.replace(s) { it.groupValues[1] }
            s = SPACE_AFTER_OPENING.replace(s) { it.groupValues[1] }
            return s.trim()
        }

        /** Whether [group] says how a name is spoken rather than what it means. */
        private fun spoken(group: String): Boolean =
            group.any { it in PHONETIC } || '[' in group || SPOKEN_WORDS.containsMatchIn(group)

        /**
         * [paragraph] in sentences. A sentence ends at a full stop, a question or exclamation mark
         * (with the quotes and brackets that close after it) followed by a space and the capital,
         * figure or quote another starts with. A full stop after an abbreviation, an initial or a
         * number of up to three figures (a German date or century: "1. Juli", "20. Jahrhundert")
         * ends none.
         */
        internal fun sentences(paragraph: String): List<String> {
            val out = ArrayList<String>()
            var start = 0
            var i = 0
            val p = paragraph
            while (i < p.length) {
                val c = p[i]
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    var j = i + 1
                    while (j < p.length && p[j] in CLOSERS) j++
                    if (j < p.length && p[j] == ' ') {
                        var k = j
                        while (k < p.length && p[k] == ' ') k++
                        if (k < p.length && opens(p[k]) && !(c == '.' && shortened(p, start, i))) {
                            out += p.substring(start, j).trim()
                            start = k
                            i = k
                            continue
                        }
                    }
                }
                i++
            }
            if (start < p.length) out += p.substring(start).trim()
            return out.filter { it.isNotEmpty() }
        }

        /** Whether [c] can begin a sentence. */
        private fun opens(c: Char): Boolean = c.isUpperCase() || c.isDigit() || c in OPENERS

        /** Whether the full stop at [dot] ends a word that is shortened (from [start], the sentence's). */
        private fun shortened(p: String, start: Int, dot: Int): Boolean {
            var b = dot
            while (b > start && (p[b - 1].isLetterOrDigit() || p[b - 1] == '.')) b--
            val word = p.substring(b, dot)
            return when {
                word.isEmpty() -> false
                // "z.B.", "U.S.", "e.g.": a dot inside the word.
                '.' in word -> true
                word.all { it.isDigit() } -> word.length <= 3
                // An initial: "J. S. Bach".
                word.length == 1 && word[0].isLetter() -> true
                else -> word.lowercase() in ABBREVIATIONS
            }
        }

        private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")
        private val TAG = Regex("""</?[a-zA-Z][a-zA-Z0-9]*\b[^>]*>""")
        private val COMMENT = Regex("""<!--[\s\S]*?-->""")
        private val HIDDEN = Regex("""<(script|style|sup)\b[^>]*>[\s\S]*?</\1\s*>""", RegexOption.IGNORE_CASE)
        private val BREAK = Regex("""<\s*(br|/?p|/?div|/?li|/?h[1-6]|/?blockquote)\b[^>]*>""", RegexOption.IGNORE_CASE)

        /** The named entities a text about an artist uses; a soft hyphen goes. */
        private val ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0", "shy" to "",
            "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026",
            "lsquo" to "\u2018", "rsquo" to "\u2019", "sbquo" to "\u201a", "ldquo" to "\u201c", "rdquo" to "\u201d", "bdquo" to "\u201e",
            "laquo" to "\u00ab", "raquo" to "\u00bb",
        )

        private val PARENTHESES = Regex("""\s*\([^()]*\)""")
        private val BRACKETS = Regex("""\s*\[[^\[\]]*]""")
        private val SPACES = Regex(""" {2,}""")
        private val SPACE_BEFORE = Regex(""" ([,.;:!?)\]])""")
        private val SPACE_AFTER_OPENING = Regex("""([(\[]) """)

        /** Words that name a pronunciation, in the article's own words. */
        private val SPOKEN_WORDS = Regex("""(?i)\b(pronounced|pronunciation|listen|aussprache|anhören)\b""")

        /** Letters of the phonetic alphabet that ordinary words in Latin script don't use, and the symbol for a recording. */
        private const val PHONETIC = "ˈˌːˑəɛɪʊʃʒŋɔʁɐʏɑɒʌɜɝɚɡɹɾʔʎɲɣɸʂʐɕʑɨʉɯɤɘɵɞʕʋɻɭɳɖʈɟʝɫʍɥⓘ"

        /** What may close after a sentence's last mark. */
        private const val CLOSERS = "\"'”’»«)]"

        /** What may open a sentence besides a capital or a figure. */
        private const val OPENERS = "\"'„“‚‘«»(["

        /** Abbreviations that end with a full stop inside a sentence, in English and German, lowercase. */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "no", "nos", "vol", "feat", "ft",
            "approx", "inc", "ltd", "co", "corp", "mt", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep",
            "sept", "oct", "nov", "dec", "bzw", "ca", "nr", "evtl", "ggf", "inkl", "usw", "vgl", "sog", "insb",
            "dt", "engl", "frz", "geb", "gest", "jh", "jhd", "hl", "bspw", "bzgl", "ehem", "eigtl", "abk",
        )
    }
}

/** What is known about the artist of the track with the key [key]: their [bio], or null for none. */
data class SongBio(val key: String, val bio: ArtistBio?)

/**
 * How long each page of a bio stands beside the cover: long enough to read it at leisure, a
 * moment to find it and then [MS_PER_CHAR] for each of its characters, from [MIN_MS] to [MAX_MS].
 * The pages follow the song, so they stand still in a pause and come again after the last.
 */
object BioPacing {
    const val START_MS = 3_000L
    const val MS_PER_CHAR = 65L
    const val MIN_MS = 10_000L
    const val MAX_MS = 40_000L

    /** How long a page of [chars] characters stands (ms). */
    fun durationMs(chars: Int): Long = (START_MS + chars * MS_PER_CHAR).coerceIn(MIN_MS, MAX_MS)

    /**
     * The page that stands [positionMs] into the song, of pages [chars] long: each after the
     * other for its [durationMs], from the first again once all have stood. The first while the
     * position is unknown.
     */
    fun pageAt(positionMs: Long?, chars: IntArray): Int {
        if (chars.size <= 1 || positionMs == null || positionMs <= 0L) return 0
        var total = 0L
        for (c in chars) total += durationMs(c)
        var t = positionMs % total
        for (i in chars.indices) {
            val d = durationMs(chars[i])
            if (t < d) return i
            t -= d
        }
        return chars.size - 1
    }
}
