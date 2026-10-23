package io.github.hazymorning.klangbild.text

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's words in the language it shows, and that language's formats: numbers, percent,
 * dates and the time of day (in 12 or 24 hours, as the system is set). Text made outside
 * Compose (the menus, the status line, the setup page) and inside it comes from here, so a new
 * language applies everywhere at once.
 */
class Texts(
    private val resources: Resources,
    /** The language the words are in, with the system's region where it has one (formats follow it). */
    val locale: Locale,
    /** Times of day in 24 hours (else 12 hours with AM and PM). */
    val clock24: Boolean,
) {
    operator fun get(@StringRes id: Int): String = resources.getString(id)

    fun format(@StringRes id: Int, vararg args: Any): String = fontSafe(resources.getString(id, *args))

    /** The plural form of [id] for [count], with [count] as its first argument. */
    fun plural(@PluralsRes id: Int, count: Int): String = fontSafe(resources.getQuantityString(id, count, count))

    /** The plural form of [id] for [count], with [args] as its arguments. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = fontSafe(resources.getQuantityString(id, count, *args))

    /**
     * [count] in words where the language has a word for it ("Three" in English, its German word
     * in German), else in figures.
     */
    fun countWord(count: Int): String =
        resources.getStringArray(R.array.count_words).getOrNull(count) ?: NumberFormat.getIntegerInstance(locale).format(count)

    /** "35%", "35 %": a whole percent as the language writes it. */
    fun percent(percent: Int): String = fontSafe(NumberFormat.getPercentInstance(locale).format(percent / 100.0))

    /** [value] with at most [maxFractionDigits] decimals: "1.5", "1,25". */
    fun decimal(value: Double, maxFractionDigits: Int): String =
        fontSafe(NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = maxFractionDigits }.format(value))

    /**
     * [value] with exactly [fractionDigits] decimals, grouped as the language groups and rounded
     * half away from zero, as Home Assistant shows it: "1,234.50", "1.234,50".
     */
    fun number(value: Double, fractionDigits: Int): String =
        fontSafe(
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
                roundingMode = java.math.RoundingMode.HALF_UP
            }.format(value),
        )

    /** The time of day at [epochMs]: "21:47", or "9:47 PM" in 12 hours. No leading zero, as a clock on a wall shows it. */
    fun clock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fontSafe((if (clock24) clock24Format else clock12Format).format(Instant.ofEpochMilli(epochMs).atZone(zone)))

    /** The figures of the time of day alone, for the large rest clock: "21:47", or "9:47" in 12 hours. */
    fun clockFigures(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        (if (clock24) clock24Format else figures12Format).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * The date at [epochMs] with the weekday, as the language writes it: "Saturday, October 10"
     * in English, day before month in German.
     */
    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fontSafe(dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone)))

    private val clock24Format = DateTimeFormatter.ofPattern("H:mm", locale)
    private val clock12Format = DateTimeFormatter.ofPattern("h:mm a", locale)
    private val figures12Format = DateTimeFormatter.ofPattern("h:mm", locale)
    private val dateFormat = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)

    companion object {
        /** The languages the app's words exist in; English is the default. */
        val LANGUAGES = setOf("en", "de")

        /**
         * The words for [language] and the system's locales [system], with times as [clock24]
         * says. A language the app does not have falls back to English, as Android's own
         * resource lookup does.
         */
        fun of(context: Context, language: AppLanguage, system: LocaleList, clock24: Boolean? = null): Texts {
            val wanted = language.tag?.let { LocaleList(Locale.forLanguageTag(it)) } ?: system
            val config = Configuration(context.resources.configuration).apply { setLocales(wanted) }
            val localized = context.createConfigurationContext(config)
            return Texts(
                resources = localized.resources,
                locale = formatLocale(wanted, system),
                clock24 = clock24 ?: android.text.format.DateFormat.is24HourFormat(localized),
            )
        }

        /** Texts in the language and with the clock of [context] itself (screens rendered on their own, tests). */
        fun of(context: Context): Texts {
            val locales = context.resources.configuration.locales
            return Texts(context.resources, formatLocale(locales, locales), android.text.format.DateFormat.is24HourFormat(context))
        }

        /**
         * The locale whose formats go with the words: the first of [wanted] in a language the
         * app has, with the system's region where the system speaks that language too
         * (British English keeps its dates); English when none is.
         */
        fun formatLocale(wanted: LocaleList, system: LocaleList): Locale {
            val language = (0 until wanted.size()).map { wanted[it] }.firstOrNull { it.language in LANGUAGES }?.language ?: "en"
            val fromSystem = (0 until system.size()).map { system[it] }.firstOrNull { it.language == language && it.country.isNotEmpty() }
            return fromSystem ?: (0 until wanted.size()).map { wanted[it] }.firstOrNull { it.language == language } ?: Locale.forLanguageTag(language)
        }

        /** The app's two faces have none of the narrow or thin spaces the platform's formats use: a no-break space instead. */
        fun fontSafe(s: String): String = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
    }
}
