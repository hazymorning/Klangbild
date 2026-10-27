package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.text.TestTexts
import kotlin.test.Test

class TimeTextTest {
    @Test
    fun `times read like a clock`() {
        assertThat(TimeText.formatClock(7_000)).isEqualTo("0:07")
        assertThat(TimeText.formatClock(221_400)).isEqualTo("3:41")
        assertThat(TimeText.formatClock(3_725_000)).isEqualTo("1:02:05")
        assertThat(TimeText.formatClock(-500)).isEqualTo("0:00")
    }

    @Test
    fun `spoken word at another speed says so, in the language's figures`() {
        val de = TestTexts.german
        assertThat(TimeText.speed(1f, de)).isNull()
        assertThat(TimeText.speed(1.004f, de)).isNull()
        assertThat(TimeText.speed(1.5f, de)).isEqualTo("Tempo\u00A01,5×")
        assertThat(TimeText.speed(1.25f, de)).isEqualTo("Tempo\u00A01,25×")
        assertThat(TimeText.speed(2f, de)).isEqualTo("Tempo\u00A02×")
        assertThat(TimeText.speed(0.75f, de)).isEqualTo("Tempo\u00A00,75×")
        assertThat(TimeText.speed(1.5f, TestTexts.english())).isEqualTo("Speed\u00A01.5×")
    }

    @Test
    fun `the separator stays with the word before it`() {
        assertThat(TimeText.SEPARATOR).isEqualTo(" · ")
    }
}
