package io.github.hazymorning.klangbild.domain

import doist.x.normalize.Form
import doist.x.normalize.normalize
import io.github.hazymorning.klangbild.text.Plurals
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import kotlin.math.roundToInt

/**
 * Where the music plays and how loud. A group is named the way music apps commonly do it: this
 * speaker plus the number of the others ("Living room + 2"), both names when there is just
 * one more, "Everywhere" when every speaker of the house plays. A speaker playing alone needs
 * no label: the screen hangs in its room.
 */
object SpeakerLabel {

    /**
     * "Living room + 5", "Living room + Kitchen"; a group with a name of its own is called by
     * it ("Ground floor"). Null while the speaker plays alone.
     */
    fun group(group: SpeakerGroup?, t: Texts): String? {
        group?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (group?.everywhere == true) return t[Strings.groupEverywhere]
        val others = group?.others?.takeIf { it.isNotEmpty() } ?: return null
        val self = group.self?.let(::roomName)
        val single = others.singleOrNull()?.let(::roomName)
        return when {
            self == null -> (others.size + 1).let { n -> t.plural(Plurals.groupSpeakers, n, t.countWord(n)) }
            single != null && single != self -> "$self\u00A0+ $single"
            else -> "$self\u00A0+\u00A0${others.size}"
        }
    }

    /**
     * True if [queueName] names something else than the speaker called [ownName]: then the
     * speaker plays from another player's queue, a group's. Spellings of the same name
     * ("Living room" and "Living room speaker") count as the same.
     */
    fun isOtherName(queueName: String, ownName: String?): Boolean {
        val q = key(queueName)
        if (q.isEmpty()) return false
        if (ownName == null) return true
        return listOfNotNull(key(ownName), roomName(ownName)?.let(::key)).filter { it.isNotEmpty() }
            .none { own -> own == q || own.contains(q) || q.contains(own) }
    }

    private fun key(s: String): String =
        s.lowercase().normalize(Form.NFKD).filter { it.isLetterOrDigit() }

    /** "35%" (or "35 %", as the language writes it), or "muted". */
    fun volume(level: Float?, muted: Boolean, t: Texts): String? = when {
        muted -> t[Strings.volumeMuted]
        level == null -> null
        else -> t.percent((level * 100f).roundToInt().coerceIn(0, 100))
    }

    /**
     * Words that name the device rather than the room ("Living room speaker" is "Living room"),
     * in the languages speakers are named in.
     */
    private val generic = setOf(
        "lautsprecher", "speaker", "speakers", "box", "boxen", "player", "mediaplayer", "musik", "music",
        "ma", "sonos", "homepod", "echo", "nest", "chromecast", "google", "alexa", "airplay", "snapcast",
        "squeezebox", "wiim", "heos", "musiccast", "bluesound", "raumfeld", "mini", "one",
    )

    private val note = Regex("""\s*[(\[]([^)\]]*)[)\]]""")

    /**
     * The room in a speaker's name: generic device words and notes in brackets dropped. If
     * nothing is left, the note is the room ("One (Bath)" is "Bath"), and failing that the
     * full name.
     */
    fun roomName(name: String): String? {
        val trimmed = name.trim().ifEmpty { return null }
        val main = clean(note.replace(trimmed, ""))
        if (main != null) return main
        return note.findAll(trimmed).firstNotNullOfOrNull { clean(it.groupValues[1]) } ?: trimmed
    }

    private fun clean(text: String): String? = text.trim().split(Regex("""\s+""")).mapNotNull { word ->
        word.split('-')
            .filter { it.isNotEmpty() && it.lowercase() !in generic }
            .joinToString("-") { stripSuffix(it) }
            .ifEmpty { null }
    }.joinToString(" ").ifEmpty { null }

    /** A device word grown onto the room (as in German compound names, or "Kitchenspeaker") is cut off. */
    private fun stripSuffix(word: String): String {
        val lower = word.lowercase()
        for (suffix in listOf("lautsprecher", "speaker")) {
            if (lower.endsWith(suffix) && word.length - suffix.length >= 3) return word.dropLast(suffix.length)
        }
        return word
    }
}
