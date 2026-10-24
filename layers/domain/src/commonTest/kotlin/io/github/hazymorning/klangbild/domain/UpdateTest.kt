package io.github.hazymorning.klangbild.domain

import assertk.assertThat
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
    fun `a build of one's own and anything else names no version`() {
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
        // A build of one's own looks for none, and so is offered none.
        assertThat(info.copy(name = "2026.10.24 (abc1234)").available).isNull()
    }
}
