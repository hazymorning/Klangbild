package io.github.hazymorning.klangbild.landscape

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import kotlin.math.abs
import kotlin.test.Test

class CloudFieldTest {
    @Test
    fun `a fuller sky has more clouds`() {
        assertThat(CloudField.density(0f)).isGreaterThan(0f)
        assertThat(CloudField.density(0.5f)).isGreaterThan(CloudField.density(0.2f))
        assertThat(CloudField.density(1f)).isLessThanOrEqualTo(1f)
        val few = (0 until 600).sumOf { CloudField.places(it * 20.0, CloudField.density(0.1f)).size }
        val many = (0 until 600).sumOf { CloudField.places(it * 20.0, CloudField.density(0.9f)).size }
        assertThat(many).isGreaterThan(few * 2)
    }

    @Test
    fun `clouds drift on smoothly and nothing appears or vanishes in the picture`() {
        val density = CloudField.density(0.6f)
        var t = 1_000.0
        repeat(2_000) {
            val before = CloudField.places(t, density)
            val after = CloudField.places(t + 1.0 / 30.0, density)
            for (c in after) {
                if (c.x + 420f * c.scale < -PaperMotif.OVERSCAN || c.x > PaperMotif.WIDTH + PaperMotif.OVERSCAN) continue
                // The same cloud a frame earlier: a hair to the right (at most 6 dp a second), as full as now.
                val was = before.firstOrNull { it.design == c.design && abs(it.y - c.y) < 0.01f && it.x - c.x in 0f..0.2f }
                assertThat(was).isNotNull()
                assertThat(abs(was!!.alpha - c.alpha)).isLessThan(0.01f)
            }
            t += 7.3
        }
    }

    @Test
    fun `the sky never repeats itself`() {
        // The strips' designs along a lane, as they come: no period, however long one looks.
        val density = CloudField.density(1f)
        // Each lane has its own scale; a strip entering on the right is seen once or twice.
        val seen = HashMap<Float, MutableList<Int>>()
        var t = 0.0
        while (t < 60_000.0) {
            for (c in CloudField.places(t, density)) {
                if (c.x > PaperMotif.WIDTH) seen.getOrPut(c.scale) { ArrayList() }.let { if (it.isEmpty() || it.last() != c.design) it += c.design }
            }
            t += 20.0
        }
        assertThat(seen.values.count { it.size >= 60 }).isGreaterThanOrEqualTo(2)
        assertThat(seen).isNotEmpty()
        for (designs in seen.values) {
            if (designs.size < 60) continue
            for (period in 1..designs.size / 3) {
                val repeats = (0 until designs.size - period).all { designs[it] == designs[it + period] }
                assertThat(repeats).isFalse()
            }
        }
    }
}
