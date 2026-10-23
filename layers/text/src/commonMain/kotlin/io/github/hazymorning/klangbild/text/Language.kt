package io.github.hazymorning.klangbild.text

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The language the app shows: the system's, or one chosen in the menu. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    GERMAN("de"),
    ;

    /** The locales whose language the words are in: the one chosen, else the system's. */
    internal fun wanted(system: List<Locale>): List<Locale> = tag?.let { listOf(Locale.forLanguageTag(it)) } ?: system

    companion object {
        /** The choice a language tag stands for ("de-AT" is German); anything else is the system's. */
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.tag != null && tag?.substringBefore('-') == it.tag } ?: SYSTEM
    }
}

/** A language, with the region whose formats it takes where it has one: "en-GB", "de". */
class Locale(val language: String, val country: String = "") {
    /**
     * The whole tag this locale was read from, with its script and extensions: the platform's
     * own preferences (a 24 hour clock, say) may be among them.
     */
    internal var tag: String = toLanguageTag()
        private set

    /** "en-GB", or "de" without a region. */
    fun toLanguageTag(): String = if (country.isEmpty()) language else "$language-$country"

    override fun equals(other: Any?): Boolean = other is Locale && other.language == language && other.country == country

    override fun hashCode(): Int = language.hashCode() * 31 + country.hashCode()

    override fun toString(): String = toLanguageTag()

    companion object {
        /** The language and region of a BCP 47 tag ("de-AT", "en-Latn-GB-u-mu-celsius"); its script and extensions don't change the words or their formats. */
        fun forLanguageTag(tag: String): Locale {
            val parts = tag.split('-', '_')
            val country = parts.drop(1).takeWhile { it.length > 1 }
                .firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }
            return Locale(parts[0].lowercase(), country?.uppercase().orEmpty()).also { it.tag = tag }
        }
    }
}

/** Where the language choice is kept, and the system's own languages and clock. */
interface LanguageStore {
    fun get(): AppLanguage
    fun set(language: AppLanguage)

    /** The system's languages, whatever the app shows. */
    fun systemLocales(): List<Locale>

    /** Whether the system shows the time in 24 hours for [locales]: as it is set, else as their region does. */
    fun clock24(locales: List<Locale>): Boolean
}

/**
 * The texts the app shows now, for the language chosen and the system's languages and clock.
 * [refresh] after anything that may change them (a new choice, a configuration change, the
 * system's 12 or 24 hour setting).
 */
class Localizer(private val store: LanguageStore) {
    private val _texts = MutableStateFlow(build())
    val texts: StateFlow<Texts> = _texts.asStateFlow()

    val language: AppLanguage get() = store.get()

    fun setLanguage(language: AppLanguage) {
        if (language != store.get()) store.set(language)
        refresh()
    }

    fun refresh() {
        _texts.value = build()
    }

    private fun build(): Texts {
        val language = store.get()
        val system = store.systemLocales()
        return Texts.of(language, system, store.clock24(language.wanted(system)))
    }
}
