package io.github.hazymorning.klangbild.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The language chosen in the menu: kept by the platform from API 33, by the app before, and applied at once. */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun switchesAtOnce() {
        val store = PlatformLanguageStore(context)
        val localizer = Localizer(context, store)
        // The test system speaks English.
        assertThat(localizer.language).isEqualTo(AppLanguage.SYSTEM)
        assertThat(localizer.texts.value[R.string.menu_heading]).isEqualTo("Settings")

        localizer.setLanguage(AppLanguage.GERMAN)
        assertThat(store.get()).isEqualTo(AppLanguage.GERMAN)
        assertThat(localizer.texts.value[R.string.menu_heading]).isEqualTo("Einstellungen")
        assertThat(localizer.texts.value.locale.language).isEqualTo("de")
        // A new store (the app started again) still knows the choice.
        assertThat(PlatformLanguageStore(context).get()).isEqualTo(AppLanguage.GERMAN)

        localizer.setLanguage(AppLanguage.ENGLISH)
        assertThat(localizer.texts.value[R.string.menu_heading]).isEqualTo("Settings")

        localizer.setLanguage(AppLanguage.SYSTEM)
        assertThat(store.get()).isEqualTo(AppLanguage.SYSTEM)
        assertThat(localizer.texts.value[R.string.menu_heading]).isEqualTo("Settings")
    }

    @Test
    @Config(sdk = [34])
    fun `from API 33 the platform keeps the choice`() = switchesAtOnce()

    @Test
    @Config(sdk = [28])
    fun `below API 33 the app keeps the choice itself`() = switchesAtOnce()

    @Test
    @Config(sdk = [34])
    fun `a language tag picks the language, anything else follows the system`() {
        assertThat(AppLanguage.of("de-AT")).isEqualTo(AppLanguage.GERMAN)
        assertThat(AppLanguage.of("en-GB")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.of("fr")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.of(null)).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    @Config(sdk = [34])
    fun `formats follow the language, with the system's region where it speaks it`() {
        val british = Texts.formatLocale(android.os.LocaleList.forLanguageTags("en"), android.os.LocaleList.forLanguageTags("en-GB,de-DE"))
        assertThat(british.toLanguageTag()).isEqualTo("en-GB")
        val austrian = Texts.formatLocale(android.os.LocaleList.forLanguageTags("de"), android.os.LocaleList.forLanguageTags("de-AT"))
        assertThat(austrian.toLanguageTag()).isEqualTo("de-AT")
        // A system language the app does not have: its words and formats are English.
        val french = Texts.formatLocale(android.os.LocaleList.forLanguageTags("fr-FR"), android.os.LocaleList.forLanguageTags("fr-FR"))
        assertThat(french.language).isEqualTo("en")
        val de = TestTexts.german
        assertThat(de.decimal(1.25, 2)).isEqualTo("1,25")
        assertThat(de.date(1_791_655_200_000L, java.time.ZoneId.of("Europe/Berlin"))).isEqualTo("Samstag, 10. Oktober")
        val en = TestTexts.english()
        assertThat(en.decimal(1.25, 2)).isEqualTo("1.25")
        assertThat(en.date(1_791_655_200_000L, java.time.ZoneId.of("Europe/Berlin"))).isEqualTo("Saturday, October 10")
    }
}
