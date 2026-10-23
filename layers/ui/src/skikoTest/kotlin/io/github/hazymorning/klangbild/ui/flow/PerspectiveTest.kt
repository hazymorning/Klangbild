package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

/** The perspective a card is drawn through takes its corners onto the quad it fills. */
class PerspectiveTest {
    private val card = floatArrayOf(0f, 0f, 300f, 0f, 300f, 300f, 0f, 300f)

    @Test
    fun `a card's corners land on the corners of its quad`() {
        val quads = listOf(
            // Turned away to the right, as Cover Flow turns the cards beside the middle one.
            floatArrayOf(100f, 50f, 380f, 20f, 380f, 340f, 100f, 310f),
            floatArrayOf(-20f, 30f, 200f, 0f, 220f, 280f, 0f, 300f),
            // Only moved and scaled.
            floatArrayOf(10f, 10f, 160f, 10f, 160f, 160f, 10f, 160f),
        )
        for (quad in quads) {
            val matrix = Matrix()
            assertThat(Perspective().map(card, quad, matrix)).isTrue()
            for (i in 0 until 4) {
                val corner = matrix.map(Offset(card[2 * i], card[2 * i + 1]))
                assertThat(corner.x).isCloseTo(quad[2 * i], 0.01f)
                assertThat(corner.y).isCloseTo(quad[2 * i + 1], 0.01f)
            }
        }
    }

    @Test
    fun `a quad without area leaves the matrix as it was`() {
        val matrix = Matrix().apply { translate(5f, 7f) }
        val before = matrix.values.toList()
        val line = floatArrayOf(0f, 0f, 100f, 0f, 200f, 0f, 300f, 0f)
        assertThat(Perspective().map(card, line, matrix)).isFalse()
        assertThat(matrix.values.toList()).isEqualTo(before)
    }
}
