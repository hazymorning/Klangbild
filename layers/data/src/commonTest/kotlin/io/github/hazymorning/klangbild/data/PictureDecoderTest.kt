package io.github.hazymorning.klangbild.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** How much a picture is subsampled as it decodes: never below a large square's size, and never more pixels than one. */
class PictureDecoderTest {
    @Test
    fun `a large picture is halved as long as it stays at least half the largest size`() {
        assertThat(sampleSize(3000, 3000)).isEqualTo(2)
        assertThat(sampleSize(9000, 9000)).isEqualTo(8)
        assertThat(sampleSize(2048, 2048)).isEqualTo(1)
        assertThat(sampleSize(640, 640)).isEqualTo(1)
    }

    @Test
    fun `odd shapes are subsampled to the size of a large square`() {
        // 9000 by 600 has more pixels than 2048 by 2048; 4500 by 300 has fewer.
        assertThat(sampleSize(9000, 600)).isEqualTo(2)
        assertThat(sampleSize(600, 9000)).isEqualTo(2)
    }
}
