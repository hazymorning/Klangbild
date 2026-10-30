package io.github.hazymorning.klangbild.domain

/**
 * A release's version as its tag names it, by the calendar: 2026.11.0 is the first release in
 * November 2026, 2026.11.1 the next that month, and 2026.11.0-beta.1 a beta before 2026.11.0.
 */
data class AppVersion(val year: Int, val month: Int, val number: Int, val beta: Int? = null) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.number }, { it.beta ?: Int.MAX_VALUE })

    companion object {
        private val PATTERN = Regex("""v?(\d{4})\.(\d{1,2})\.(\d{1,3})(?:-beta\.(\d{1,3}))?""")

        /** The version [text] names ("2026.11.0", "v2026.11.0-beta.1"); null for anything else, like a local build. */
        fun parse(text: String?): AppVersion? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (year, month, number, beta) = m.destructured
            return AppVersion(year.toInt(), month.toInt(), number.toInt(), beta.toIntOrNull())
        }
    }
}

/** Where Klangbild stands with its own new versions, as the menu says it. */
data class UpdateInfo(
    /** The installed version as Android names it: a release's ("2026.11.0") or a local build's. */
    val name: String = "",
    /** The newest release GitHub named (its tag, "v2026.11.0"), once it has. */
    val latest: String? = null,
    val state: UpdateState = UpdateState.IDLE,
    /** The notes of the [latest] release, as they are written on GitHub. */
    val notes: String? = null,
) {
    /** The installed version when it is a release; a local build never looks for updates. */
    val installed: AppVersion? get() = AppVersion.parse(name)

    /** The newest release's tag when it is newer than the version installed. */
    val available: String?
        get() {
            val now = installed ?: return null
            val newest = AppVersion.parse(latest) ?: return null
            return latest.takeIf { newest > now }
        }
}

/** What Klangbild is doing about a new version. */
enum class UpdateState { IDLE, CHECKING, DOWNLOADING, FAILED }

/** A release GitHub names: its [tag] ("v2026.11.0") and its [notes], as they are written there. */
data class Release(val tag: String, val notes: String?)

/** One point of a release's notes: the part it stands under ("New"), its feature's [name] if it starts with one, and the rest. */
data class NotePoint(val part: String?, val name: String?, val text: String)

/** Release notes as `docs/releases` writes them: parts under `### `, points after `- `, a feature's name in bold. */
object ReleaseNotes {
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")

    /** The points of [markdown], in order; none for notes written otherwise. */
    fun points(markdown: String?): List<NotePoint> {
        val points = ArrayList<NotePoint>()
        var part: String? = null
        var point: StringBuilder? = null
        var pointPart: String? = null
        fun finish() {
            val text = point?.toString()?.trim().orEmpty()
            point = null
            if (text.isEmpty()) return
            val bold = text.takeIf { it.startsWith("**") }?.indexOf("**", 2)?.takeIf { it > 2 }
            val name = bold?.let { plain(text.substring(2, it)) }
            val rest = plain(if (bold == null) text else text.substring(bold + 2))
            if (name != null || rest.isNotEmpty()) points += NotePoint(pointPart, name, rest)
        }
        for (raw in markdown.orEmpty().lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> {
                    finish()
                    part = plain(line.trimStart('#')).ifEmpty { null }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    finish()
                    point = StringBuilder(line.drop(2))
                    pointPart = part
                }
                line.isEmpty() -> finish()
                else -> point?.append(' ')?.append(line)
            }
        }
        finish()
        return points
    }

    /** [text] without Markdown's marks (a link's text, no bold or code), with curly quotes and apostrophes. */
    private fun plain(text: String): String {
        val s = text.replace(link, "$1").replace("**", "").replace("`", "").trim()
        var quoted = false
        return buildString {
            for ((i, c) in s.withIndex()) {
                when {
                    c == '"' -> {
                        append(if (quoted) '\u201D' else '\u201C')
                        quoted = !quoted
                    }
                    c == '\'' && i > 0 && s[i - 1].isLetter() -> append('\u2019')
                    else -> append(c)
                }
            }
        }
    }
}
