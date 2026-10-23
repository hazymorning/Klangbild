package io.github.hazymorning.klangbild.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test

/** The language chosen in the menu: kept by the platform from API 33, by the app before, and applied at once. */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun switchesAtOnce() {
        val store = PlatformLanguageStore(context)
        val localizer = Localizer(store)
        // The test system speaks English.
        assertThat(localizer.language).isEqualTo(AppLanguage.SYSTEM)
        assertThat(localizer.texts.value[Strings.menuHeading]).isEqualTo("Settings")

        localizer.setLanguage(AppLanguage.GERMAN)
        assertThat(store.get()).isEqualTo(AppLanguage.GERMAN)
        assertThat(localizer.texts.value[Strings.menuHeading]).isEqualTo("Einstellungen")
        assertThat(localizer.texts.value.locale.language).isEqualTo("de")
        // A new store (the app started again) still knows the choice.
        assertThat(PlatformLanguageStore(context).get()).isEqualTo(AppLanguage.GERMAN)

        localizer.setLanguage(AppLanguage.ENGLISH)
        assertThat(localizer.texts.value[Strings.menuHeading]).isEqualTo("Settings")

        localizer.setLanguage(AppLanguage.SYSTEM)
        assertThat(store.get()).isEqualTo(AppLanguage.SYSTEM)
        assertThat(localizer.texts.value[Strings.menuHeading]).isEqualTo("Settings")
    }

    @Test
    @Config(sdk = [34])
    fun `from API 33 the platform keeps the choice`() = switchesAtOnce()

    @Test
    @Config(sdk = [28])
    fun `below API 33 the app keeps the choice itself`() = switchesAtOnce()
}
