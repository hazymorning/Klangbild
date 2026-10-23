package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.LocaleList
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.Locale
import io.github.hazymorning.klangbild.text.Texts

/**
 * The texts in the language shown; a screen rendered on its own takes the platform's languages,
 * with the clock their region has.
 */
@Composable
fun texts(): Texts {
    LocalTexts.current?.let { return it }
    val system = LocaleList.current
    return remember(system) {
        val locales = system.map { Locale.forLanguageTag(it.toLanguageTag()) }
        Texts.of(AppLanguage.SYSTEM, locales, Texts.clock24Of(Texts.formatLocale(locales, locales)))
    }
}
