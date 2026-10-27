package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.test.Test

class SunClockTest {
    @Test
    fun `by the clock the sun is up from before eight to after six, highest at one`() {
        assertThat(SunClock.elevation(7, 30)).isLessThan(0.0)
        assertThat(SunClock.elevation(7, 45)).isGreaterThan(0.0)
        assertThat(SunClock.elevation(18, 15)).isGreaterThan(0.0)
        assertThat(SunClock.elevation(18, 30)).isLessThan(0.0)
        assertThat(SunClock.elevation(13, 0)).isGreaterThan(SunClock.elevation(12, 0))
        assertThat(SunClock.elevation(13, 0)).isGreaterThan(SunClock.elevation(14, 0))
    }
}
