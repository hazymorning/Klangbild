package io.github.hazymorning.klangbild.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test
    fun `grows exponentially with jitter and caps`() {
        val b = Backoff(baseMs = 1000, maxMs = 60_000, random = Random(1))
        val delays = List(10) { b.nextDelayMs() }
        val caps = listOf(1000L, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000, 60000)
        delays.zip(caps).forEach { (d, cap) ->
            assertThat(d).isAtLeast(cap / 2)
            assertThat(d).isAtMost(cap)
        }
        assertThat(b.attempt).isEqualTo(10)
        b.reset()
        assertThat(b.attempt).isEqualTo(0)
        assertThat(b.nextDelayMs()).isAtMost(1000)
    }
}
