package io.github.hazymorning.klangbild.text

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** The language a tag names, and the formats that go with the strings. */
class LanguageTagsTest {
    private fun tags(list: String) = list.split(',').map(Locale::forLanguageTag)

    @Test
    fun `a language tag picks the language, anything else follows the system`() {
        assertThat(AppLanguage.of("de-AT")).isEqualTo(AppLanguage.GERMAN)
        assertThat(AppLanguage.of("en-GB")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.of("fr")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.of(null)).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    fun `formats follow the language, with the system's region where it speaks it`() {
        val british = Texts.formatLocale(tags("en"), tags("en-GB,de-DE"))
        assertThat(british.toLanguageTag()).isEqualTo("en-GB")
        val austrian = Texts.formatLocale(tags("de"), tags("de-AT"))
        assertThat(austrian.toLanguageTag()).isEqualTo("de-AT")
        // A system language the app does not have: its strings and formats are English.
        val french = Texts.formatLocale(tags("fr-FR"), tags("fr-FR"))
        assertThat(french.language).isEqualTo("en")
        val de = TestTexts.german
        assertThat(de.decimal(1.25, 2)).isEqualTo("1,25")
        assertThat(de.date(1_791_655_200_000L, testZone("Europe/Berlin"))).isEqualTo("Samstag, 10. Oktober")
        val en = TestTexts.english()
        assertThat(en.decimal(1.25, 2)).isEqualTo("1.25")
        assertThat(en.date(1_791_655_200_000L, testZone("Europe/Berlin"))).isEqualTo("Saturday, October 10")
    }

    @Test
    fun `the time of day in 24 hours, or in 12 as the system is set`() {
        val at = 1_791_483_667_000L
        val berlin = testZone("Europe/Berlin")
        assertThat(TestTexts.german.clock(at, berlin)).isEqualTo("20:21")
        assertThat(TestTexts.english().clock(at, berlin)).isEqualTo("8:21\u00A0PM")
        assertThat(TestTexts.english(clock24 = true).clock(at, berlin)).isEqualTo("20:21")
    }
}
