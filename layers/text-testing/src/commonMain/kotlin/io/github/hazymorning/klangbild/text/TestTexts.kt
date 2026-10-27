package io.github.hazymorning.klangbild.text

/** The app's texts for tests. */
object TestTexts {
    /** German as in Germany, 24 hours. */
    val german: Texts get() = of(AppLanguage.GERMAN, Locale("de", "DE"))

    /** English as in the United States, 12 hours unless asked otherwise. */
    fun english(clock24: Boolean = false): Texts = of(AppLanguage.ENGLISH, Locale("en", "US"), clock24)

    private fun of(language: AppLanguage, system: Locale, clock24: Boolean = true): Texts = Texts.of(language, listOf(system), clock24)
}
