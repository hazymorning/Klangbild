package io.github.hazymorning.klangbild.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import kotlin.random.Random
import kotlin.test.Test

class BackoffTest {
    @Test
    fun `grows exponentially with jitter and caps`() {
        val b = Backoff(baseMs = 1000, maxMs = 60_000, random = Random(1))
        val delays = List(10) { b.nextDelayMs() }
        val caps = listOf(1000L, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000, 60000)
        delays.zip(caps).forEach { (d, cap) ->
            assertThat(d).isGreaterThanOrEqualTo(cap / 2)
            assertThat(d).isLessThanOrEqualTo(cap)
        }
        assertThat(b.attempt).isEqualTo(10)
        b.reset()
        assertThat(b.attempt).isEqualTo(0)
        assertThat(b.nextDelayMs()).isLessThanOrEqualTo(1000)
    }
}
