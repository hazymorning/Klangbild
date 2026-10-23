package io.github.hazymorning.klangbild.landscape

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class LandscapeClockTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val start = ZonedDateTime.of(2026, 10, 10, 13, 37, 0, 0, zone).toInstant().toEpochMilli()
    private var wall = start
    private val clock = LandscapeClock(now = { wall })

    @Test
    fun `the sky is the real one`() {
        assertThat(clock.sky()).isEqualTo(start)
        wall += 60_000L
        assertThat(clock.sky()).isEqualTo(start + 60_000L)
    }

    @Test
    fun `the time-lapse lets a day pass in two minutes, from where the picture is`() {
        clock.timelapse(true)
        assertThat(clock.sky()).isEqualTo(start)
        wall += 4_000L
        val a = clock.sky()
        wall += 60_000L
        val b = clock.sky()
        assertThat((b - a).toDouble()).isWithin(1_000.0).of(LandscapeClock.DAY_MS / 2.0)
    }

    @Test
    fun `back to the real world it glides, never jumps`() {
        clock.timelapse(true)
        wall += 30_000L
        clock.timelapse(false)
        var prev = clock.sky()
        val away = abs(prev - wall)
        assertThat(away).isGreaterThan(LandscapeClock.DAY_MS / 8)
        var biggest = 0L
        repeat(100) {
            wall += 16L
            val now = clock.sky()
            biggest = maxOf(biggest, abs(now - prev))
            prev = now
        }
        // The glide's largest step per frame is a small part of the way.
        assertThat(biggest).isLessThan(away / 40)
        wall += 5_000L
        assertThat(clock.sky()).isEqualTo(wall)
    }
}
