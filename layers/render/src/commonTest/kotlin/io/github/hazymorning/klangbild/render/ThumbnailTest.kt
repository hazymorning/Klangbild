package io.github.hazymorning.klangbild.render

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** The thumbnail the colors are found in: what is clear in a picture stays clear and gives no color. */
class ThumbnailTest {
    @Test
    fun `transparency carries over and only what is seen gives the color`() {
        val red = 0xFFFF0000.toInt()
        val half = ImageOps.thumbnail(intArrayOf(red, 0, red, 0), 2, 2, 1)[0]
        assertThat(half ushr 24).isEqualTo(127)
        assertThat(half and 0xFFFFFF).isEqualTo(0xFF0000)
        assertThat(ImageOps.thumbnail(IntArray(4), 2, 2, 1)[0]).isEqualTo(0)
        // An opaque picture keeps its averages exactly.
        assertThat(ImageOps.thumbnail(intArrayOf(red, -1, red, -1), 2, 2, 1)[0]).isEqualTo(0xFFFF7F7F.toInt())
    }

}
