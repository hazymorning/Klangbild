package io.github.hazymorning.klangbild.domain

/**
 * Typographic preparation of track metadata. Streaming metadata is noisy; a calm display
 * needs a little editing:
 *
 *  - Classical titles like "Suite bergamasque, L. 75: III. Clair de lune" become a work
 *    line ("Suite bergamasque, L. 75") above the movement ("III. Clair de lune").
 *  - "(feat. X)" moves from the title to the artists.
 *  - Version noise ("2011 Remaster" after a dash, "(Live)") leaves the title and becomes a small note.
 *  - Emoji at the edges of a title are dropped ("🌙 Moonlight Tape ✨" → "Moonlight Tape").
 *  - Spaced hyphens become en dashes that stay with the preceding word, so no line ever
 *    starts with a dash (the break comes after "Presto" and its dash, never before the dash).
 *  - Abbreviations keep their number: "No. 9", "op. 125", "KV 331" never break, with a
 *    no-break space after the abbreviation's period.
 */
data class TrackText(
    val title: String,
    val work: String?,
    /** All artists, "A, B & C"; very long lists end with [others]. */
    val artists: String?,
    val album: String?,
    val version: String?,
    /** The individual artist names (featured artists included), for fitting onto one line. */
    val artistNames: List<String> = emptyList(),
    /** What stands for the artists left out ("and others", or its short German form), in the language shown. */
    val others: String,
) {
    /** One artist, or two, or the first and [others]: for the short "what comes next" line. */
    val shortArtists: String?
        get() = when {
            artistNames.isEmpty() -> artists
            artistNames.size <= 2 -> formatArtists(artistNames, others)
            else -> artistNames[0] + " " + others
        }

    /**
     * Ways to set the artists, from complete to shortest: "A, B & C", "A, B and others",
     * "A and others". The UI takes the first one that fits on a single line.
     */
    fun artistOptions(): List<String> {
        val names = artistNames
        if (names.size <= 1) return listOfNotNull(artists)
        val out = ArrayList<String>()
        out += formatArtists(names, others, limit = Int.MAX_VALUE)!!
        for (keep in names.size - 1 downTo 1) out += names.take(keep).joinToString(", ") + " " + others
        return out.distinct()
    }

    companion object {
        private val catalog = Regex(
            """(\b(BWV|Op\.|op\.|KV|K\.|L\.|Hob\.|D\.|RV|S\.|WoO|HWV|Sz\.|TrV|FP|BB|Nr\.|No\.|Nº)\s?[\dIVXLC]+)""",
        )
        private val movementStart = Regex("""^([IVXLC]+|\d{1,2})\.\s+\S""")
        private val featInTitle = Regex("""\s*[(\[](?:feat\.?|ft\.?|featuring|mit)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
        private val versionWords =
            "remaster|remastered|live|edit|version|mix|mono|stereo|demo|acoustic|akustik|instrumental|bonus track|deluxe|single"
        private val dashVersion = Regex("""\s+[-–—]\s+([^-–—]*\b(?:$versionWords)\b[^-–—]*)$""", RegexOption.IGNORE_CASE)
        private val bracketVersion = Regex("""\s*[(\[]([^()\[\]]*\b(?:$versionWords)\b[^()\[\]]*)[)\]]$""", RegexOption.IGNORE_CASE)

        /** The longest artist line [formatArtists] writes before it shortens the list. */
        const val MAX_ARTISTS_CHARS = 72

        /** The typeset text of [track]; [others] stands for artists left out of a long list. */
        fun from(track: TrackInfo, others: String): TrackText {
            var title = Emoji.trimEdges(track.title.trim())
            val artists = track.artists.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

            featInTitle.find(title)?.let { m ->
                m.groupValues[1].split(",", "&", " und ", " and ").map { it.trim() }
                    .filter { it.isNotEmpty() && artists.none { a -> a.equals(it, ignoreCase = true) } }
                    .forEach { artists += it }
                title = title.removeRange(m.range).trim()
            }

            var version: String? = null
            for (rx in listOf(dashVersion, bracketVersion)) {
                val m = rx.find(title) ?: continue
                // Never strip the whole title away.
                if (m.range.first > 0) {
                    version = m.groupValues[1].trim()
                    title = title.substring(0, m.range.first).trim()
                    break
                }
            }

            var work: String? = null
            val colon = title.lastIndexOf(": ")
            if (colon > 0) {
                val head = title.substring(0, colon).trim()
                val tail = title.substring(colon + 2).trim()
                if (tail.isNotEmpty() && (catalog.containsMatchIn(head) || movementStart.containsMatchIn(tail))) {
                    work = head
                    title = tail
                }
            }

            val album = track.album?.trim()?.let(Emoji::trimEdges)?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
            return TrackText(
                title = typeset(title.ifEmpty { track.title.trim() }),
                work = work?.let(::typeset),
                artists = formatArtists(artists, others),
                album = album?.takeUnless { work != null && it.equals(work, ignoreCase = true) }?.let(::typeset),
                version = version,
                artistNames = artists,
                others = others,
            )
        }

        /** Dashes and abbreviation spacing. */
        fun typeset(s: String): String = abbreviations(dashes(s))

        private val spacedDash = Regex("""\s+[-–—]\s+""")

        /** Spaced hyphen/dash → en dash glued to the word before it (no-break space). */
        fun dashes(s: String): String = s.replace(spacedDash, "\u00A0– ")

        private val dottedAbbreviation = Regex("""\b(Nr|No|Op|op|Hob|Bd|Vol|vol|Kap|Nº|L|K|D|S)\.\s+(?=\d|[IVXLC]+\b)""")
        private val catalogSpace = Regex("""\b(BWV|KV|RV|HWV|WoO|TrV|FP|BB|Sz)\s+(?=\d)""")

        /** Joins an abbreviation to its number with a no-break space ("No. 9", "op. 125", "KV 331"). */
        fun abbreviations(s: String): String = s
            .replace(dottedAbbreviation) { it.groupValues[1] + ".\u00A0" }
            .replace(catalogSpace) { it.groupValues[1] + "\u00A0" }

        /** "A", "A & B", "A, B & C"; lists longer than [limit] characters end with [others]. */
        fun formatArtists(names: List<String>, others: String, limit: Int = MAX_ARTISTS_CHARS): String? {
            if (names.isEmpty()) return null
            if (names.size == 1) return names[0]
            val full = names.dropLast(1).joinToString(", ") + " & " + names.last()
            if (full.length <= limit) return full
            val kept = ArrayList<String>()
            var length = 0
            for (n in names) {
                if (kept.isNotEmpty() && length + n.length + 2 > limit - others.length - 1) break
                kept += n
                length += n.length + 2
            }
            return kept.joinToString(", ") + " " + others
        }
    }
}
