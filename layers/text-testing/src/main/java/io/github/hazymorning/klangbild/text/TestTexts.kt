package io.github.hazymorning.klangbild.text

import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.util.Locale

/** The app's texts for tests (they need Robolectric: the words come from the resources). */
object TestTexts {
    /** German as in Germany, 24 hours. */
    val german: Texts get() = of(AppLanguage.GERMAN, Locale.GERMANY)

    /** English as in the United States, 12 hours unless asked otherwise. */
    fun english(clock24: Boolean = false): Texts = of(AppLanguage.ENGLISH, Locale.US, clock24)

    fun of(language: AppLanguage, system: Locale, clock24: Boolean = true): Texts =
        Texts.of(ApplicationProvider.getApplicationContext<Context>(), language, LocaleList(system), clock24)
}
