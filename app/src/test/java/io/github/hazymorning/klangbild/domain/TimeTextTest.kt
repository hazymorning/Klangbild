package io.github.hazymorning.klangbild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeTextTest {
    @Test
    fun `times read like a clock`() {
        assertThat(TimeText.formatClock(7_000)).isEqualTo("0:07")
        assertThat(TimeText.formatClock(221_400)).isEqualTo("3:41")
        assertThat(TimeText.formatClock(3_725_000)).isEqualTo("1:02:05")
        assertThat(TimeText.formatClock(-500)).isEqualTo("0:00")
    }

    @Test
    fun `the separator stays with the word before it`() {
        assertThat(TimeText.SEPARATOR).isEqualTo(" · ")
    }
}
