package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import kotlin.test.Test

class UpdateTest {
    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun `versions go by year, month and number, and a beta comes before its release`() {
        assertThat(v("v2026.11.0")).isEqualTo(AppVersion(2026, 11, 0))
        assertThat(v("2026.11.0-beta.2")).isEqualTo(AppVersion(2026, 11, 0, beta = 2))
        assertThat(v("2026.11.1")).isGreaterThan(v("2026.11.0"))
        assertThat(v("2026.12.0")).isGreaterThan(v("2026.11.9"))
        assertThat(v("2027.1.0")).isGreaterThan(v("2026.12.3"))
        assertThat(v("2026.11.0-beta.1")).isLessThan(v("2026.11.0"))
        assertThat(v("2026.11.0-beta.2")).isGreaterThan(v("2026.11.0-beta.1"))
        assertThat(v("2026.11.0-beta.1")).isGreaterThan(v("2026.10.5"))
    }

    @Test
    fun `a local build and anything else name no version`() {
        assertThat(AppVersion.parse("2026.10.24 (abc1234)")).isNull()
        assertThat(AppVersion.parse("6.4.0")).isNull()
        assertThat(AppVersion.parse("")).isNull()
        assertThat(AppVersion.parse(null)).isNull()
    }

    @Test
    fun `only a newer release is offered`() {
        val info = UpdateInfo(name = "2026.10.5", latest = "v2026.11.0")
        assertThat(info.available).isEqualTo("v2026.11.0")
        assertThat(info.copy(latest = "v2026.10.5").available).isNull()
        assertThat(info.copy(latest = null).available).isNull()
        assertThat(info.copy(name = "2026.11.0-beta.1").available).isEqualTo("v2026.11.0")
        // A local build never looks for updates, and so is offered none.
        assertThat(info.copy(name = "2026.10.24 (abc1234)").available).isNull()
    }

    @Test
    fun `release notes come in points, each under its part and with its feature's name`() {
        val notes = "### New\r\n- **Music in other rooms.** Klangbild can now show what's playing in other rooms.\r\n" +
            "- **Notifications.** Klangbild can now show [notifications](https://example.org) like \"Washer · Done\".\r\n\r\n" +
            "### Bug fixes\r\n- Turning on `Play here` sometimes left the TV off.\r\n  That should no longer happen.\r\n"
        assertThat(ReleaseNotes.points(notes)).containsExactly(
            NotePoint("New", "Music in other rooms.", "Klangbild can now show what\u2019s playing in other rooms."),
            NotePoint("New", "Notifications.", "Klangbild can now show notifications like \u201CWasher · Done\u201D."),
            NotePoint("Bug fixes", null, "Turning on Play here sometimes left the TV off. That should no longer happen."),
        )
        assertThat(ReleaseNotes.points(null)).isEmpty()
        assertThat(ReleaseNotes.points("Just a sentence.")).isEmpty()
    }
}
