package io.github.hazymorning.klangbild.speaker

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ArrivalDelaysTest {
    @Test
    fun `without delays worth a buffer it asks for the floor`() {
        val d = ArrivalDelays()
        assertThat(d.minBufferMs(0)).isEqualTo(ArrivalDelays.FLOOR_MS)
        d.add(sentUs = 0, arrivalUs = 20_000)
        assertThat(d.minBufferMs(20_000)).isEqualTo(ArrivalDelays.FLOOR_MS)
    }

    @Test
    fun `one freak delay is let go, two are covered in steps of 50 ms`() {
        val d = ArrivalDelays()
        d.add(sentUs = 0, arrivalUs = 900_000)
        assertThat(d.minBufferMs(1_000_000)).isEqualTo(ArrivalDelays.FLOOR_MS)
        d.add(sentUs = 10_000_000, arrivalUs = 10_420_000)
        assertThat(d.minBufferMs(11_000_000)).isEqualTo(450)
    }

    @Test
    fun `delays older than a minute are forgotten`() {
        val d = ArrivalDelays()
        d.add(sentUs = 0, arrivalUs = 600_000)
        d.add(sentUs = 10_000_000, arrivalUs = 10_600_000)
        assertThat(d.minBufferMs(11_000_000)).isEqualTo(600)
        assertThat(d.minBufferMs(80_000_000)).isEqualTo(ArrivalDelays.FLOOR_MS)
    }

    @Test
    fun `it never asks for more than the spec allows`() {
        val d = ArrivalDelays()
        d.add(sentUs = 0, arrivalUs = 9_000_000)
        d.add(sentUs = 10_000_000, arrivalUs = 19_000_000)
        assertThat(d.minBufferMs(20_000_000)).isEqualTo(5_000)
    }
}
