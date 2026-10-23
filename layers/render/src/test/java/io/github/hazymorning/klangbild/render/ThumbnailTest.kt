package io.github.hazymorning.klangbild.render

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.art.ChladniArt
import io.github.hazymorning.klangbild.art.stableHash
import io.github.hazymorning.klangbild.color.CoverPalette
import org.junit.Test

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

    @Test
    fun `a generated plate's colors leave out its clear corners`() {
        val px = 368
        val art = ChladniArt.render(ChladniArt.specFor("Radio"), px, seed = stableHash("Radio"))
        val analysis = CoverPalette.analyze(ImageOps.thumbnail(art, px, px, SceneBuilder.THUMB))
        // Before, the corners counted as a black making up two fifths of the plate.
        assertThat(analysis.clusters.minOf { it.lab.l }).isGreaterThan(0.1f)
    }
}
