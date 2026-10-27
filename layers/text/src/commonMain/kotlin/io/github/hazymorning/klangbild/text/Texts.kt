package io.github.hazymorning.klangbild.text

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** One of the app's texts ([Strings]), by its place in strings.xml. */
@JvmInline
value class StringId internal constructor(internal val index: Int)

/** One of the app's texts with a form for one and one for more ([Plurals]). */
@JvmInline
value class PluralId internal constructor(internal val index: Int)

/** The words of one language, in the order of [Strings] and [Plurals]. */
internal class Words(
    val strings: Array<String>,
    /** Each plural's form for one, and for any other count. */
    val one: Array<String>,
    val other: Array<String>,
    /** "Zero" to "Twelve", capitalized for the start of a label. */
    val countWords: Array<String>,
)

/**
 * The app's words in the language it shows, and that language's formats: numbers, percent,
 * dates and the time of day (in 12 or 24 hours, as the system is set). Text made outside
 * Compose (the menus, the status line, the setup page) and inside it comes from here, so a new
 * language applies everywhere at once.
 */
class Texts internal constructor(
    private val words: Words,
    /** The language the words are in, with the system's region where it has one (formats follow it). */
    val locale: Locale,
    /** Times of day in 24 hours (else 12 hours with AM and PM). */
    val clock24: Boolean,
) {
    private val region = locale.region()

    operator fun get(id: StringId): String = words.strings[id.index]

    fun format(id: StringId, vararg args: Any): String = fontSafe(formatted(words.strings[id.index], args))

    /** The plural form of [id] for [count], with [count] in words where the language has one ("Three speakers"), else in figures. */
    fun plural(id: PluralId, count: Int): String {
        val number = words.countWords.getOrNull(count) ?: region.integer(count.toLong())
        return fontSafe(formatted((if (count == 1) words.one else words.other)[id.index], arrayOf(number)))
    }

    /** "35%", "35 %": a whole percent as the language writes it. */
    fun percent(percent: Int): String = fontSafe(region.integer(percent.toLong()) + region.percentSuffix)

    /** What follows a percentage: "%", or "%" after a no-break space. */
    val percentSuffix: String get() = fontSafe(region.percentSuffix)

    /** [value] with at most [maxFractionDigits] decimals: "1.5", "1,25". */
    fun decimal(value: Double, maxFractionDigits: Int): String = fontSafe(region.decimal(value, 0, maxFractionDigits, halfUp = false))

    /**
     * [value] with exactly [fractionDigits] decimals, grouped as the language groups and rounded
     * half away from zero, as Home Assistant shows it: "1,234.50", "1.234,50".
     */
    fun number(value: Double, fractionDigits: Int): String =
        fontSafe(region.decimal(value, fractionDigits, fractionDigits, halfUp = true))

    /**
     * The time of day at [epochMs]: "21:47", or "9:47 PM" in 12 hours (a no-break space before
     * PM). No leading zero, as a clock on a wall shows it.
     */
    fun clock(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return fontSafe(if (clock24) clock24(time.hour, time.minute) else clock12(time.hour, time.minute) + "\u00A0" + region.dayPeriod(time.hour))
    }

    /** The figures of the time of day alone, for the large rest clock: "21:47", or "9:47" in 12 hours. */
    fun clockFigures(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return if (clock24) clock24(time.hour, time.minute) else clock12(time.hour, time.minute)
    }

    /**
     * The date at [epochMs] with the weekday, as the language writes it: "Saturday, October 10"
     * in English, day before month in German.
     */
    fun date(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        fontSafe(region.date(Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date))

    private fun clock24(hour: Int, minute: Int) = "$hour:${minute.toString().padStart(2, '0')}"

    private fun clock12(hour: Int, minute: Int) = "${(hour + 11) % 12 + 1}:${minute.toString().padStart(2, '0')}"

    companion object {
        /**
         * The words for [language] and the system's locales [system], with times as [clock24]
         * says. A language the app does not have falls back to English, as Android's own
         * resource lookup does.
         */
        fun of(language: AppLanguage, system: List<Locale>, clock24: Boolean): Texts {
            val locale = formatLocale(language.wanted(system), system)
            return Texts(WORDS.getValue(locale.language), locale, clock24)
        }

        /**
         * The locale whose formats go with the words: the first of [wanted] in a language the
         * app has, with the system's region where the system speaks that language too
         * (British English keeps its dates); English when none is.
         */
        fun formatLocale(wanted: List<Locale>, system: List<Locale>): Locale {
            val language = wanted.firstOrNull { it.language in WORDS }?.language ?: "en"
            val fromSystem = system.firstOrNull { it.language == language && it.country.isNotEmpty() }
            return fromSystem ?: wanted.firstOrNull { it.language == language } ?: Locale(language)
        }

        /** Whether [locale]'s region tells the time in 24 hours, where the system says nothing. */
        fun clock24Of(locale: Locale): Boolean = locale.region().clock24

        /** The app's two faces have none of the narrow or thin spaces the platform's formats use: a no-break space instead. */
        fun fontSafe(s: String): String = s.replace('\u202F', '\u00A0').replace('\u2009', '\u00A0').replace('\u2002', '\u00A0')

        /**
         * [pattern] with [args] in place of its `%s` and `%d` (`%1$s` for the first argument, and
         * so on), as Android's resources fill them in for the app's texts.
         */
        internal fun formatted(pattern: String, args: Array<out Any>): String {
            if ('%' !in pattern) return pattern
            val out = StringBuilder(pattern.length + 16)
            var next = 0
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c != '%') {
                    out.append(c)
                    i++
                    continue
                }
                var j = i + 1
                if (pattern.getOrNull(j) == '%') {
                    out.append('%')
                    i = j + 1
                    continue
                }
                while (j < pattern.length && pattern[j].isDigit()) j++
                val index = if (pattern.getOrNull(j) == '$') pattern.substring(i + 1, j).toInt() - 1 else next++
                if (pattern.getOrNull(j) == '$') j++
                when (pattern.getOrNull(j)) {
                    's', 'd' -> out.append(args[index].toString())
                    else -> throw IllegalArgumentException("Unknown format in \"$pattern\" at $i")
                }
                i = j + 1
            }
            return out.toString()
        }
    }
}
