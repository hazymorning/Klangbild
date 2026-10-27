package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ClockSkewTest {
    @Test
    fun `clock skew takes the smallest recent sample`() {
        val skew = ClockSkew(window = 3)
        assertThat(skew.offsetMs).isEqualTo(0)
        skew.addSample(3_250) // 3 s skew + 250 ms latency
        skew.addSample(3_040)
        skew.addSample(3_900)
        assertThat(skew.offsetMs).isEqualTo(3_040)
        skew.addSample(5_000)
        skew.addSample(5_100)
        skew.addSample(5_050) // window drops the old samples
        assertThat(skew.offsetMs).isEqualTo(5_000)
    }
}
