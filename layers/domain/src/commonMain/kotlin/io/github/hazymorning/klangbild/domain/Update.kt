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

        /** The version [text] names ("2026.11.0", "v2026.11.0-beta.1"); null for anything else, like a build of one's own. */
        fun parse(text: String?): AppVersion? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (year, month, number, beta) = m.destructured
            return AppVersion(year.toInt(), month.toInt(), number.toInt(), beta.toIntOrNull())
        }
    }
}

/** Where Klangbild stands with its own new versions, as the menu says it. */
data class UpdateInfo(
    /** The version installed, as Android names it: a release's ("2026.11.0"), or a build of one's own. */
    val name: String = "",
    /** The newest release GitHub named (its tag, "v2026.11.0"), once it has. */
    val latest: String? = null,
    val state: UpdateState = UpdateState.IDLE,
) {
    /** The version installed when it is a release's; a build of one's own looks for no new ones. */
    val installed: AppVersion? get() = AppVersion.parse(name)

    /** The newest release's tag when it is newer than the version installed. */
    val available: String?
        get() {
            val now = installed ?: return null
            return latest?.takeIf { (AppVersion.parse(it) ?: return null) > now }
        }
}

/** What Klangbild is doing about a new version. */
enum class UpdateState { IDLE, CHECKING, DOWNLOADING, FAILED }
