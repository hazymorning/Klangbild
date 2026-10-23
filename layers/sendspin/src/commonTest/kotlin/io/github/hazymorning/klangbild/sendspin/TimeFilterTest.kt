package io.github.hazymorning.klangbild.sendspin

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import kotlin.math.abs
import kotlin.test.Test

class TimeFilterTest {
    /**
     * The same measurements fed to the reference implementation (https://github.com/Sendspin/time-filter,
     * cpp/sendspin_time_filter.cpp at 531e270, built and run with this sequence): an offset of
     * 1e9 µs drifting by 50 ppm, a jittery round trip and an outlier now and then. Its
     * conversions after these measurements, to the microsecond.
     */
    @Test
    fun `it converts as the reference implementation does`() {
        val expected = mapOf(
            0 to (1001361450L to 1999999507L),
            1 to (1001694685L to 1999999845L),
            2 to (1002024231L to 1999999685L),
            3 to (1002293412L to 1999999320L),
            4 to (1002559358L to 1999999538L),
            49 to (1016239299L to 1999876446L),
            99 to (1031362976L to 1999890421L),
            149 to (1046235526L to 1999887175L),
            199 to (1061080009L to 1999917630L),
            249 to (1076241493L to 1999897369L),
            299 to (1091048644L to 1999907873L),
            349 to (1106569893L to 1999894530L),
            399 to (1121886238L to 1999905243L),
        )
        val f = TimeFilter()
        var s = 12345uL
        fun rnd(): Double {
            s = s * 6364136223846793005uL + 1442695040888963407uL
            return ((s shr 11) and ((1uL shl 53) - 1uL)).toLong().toDouble() / (1L shl 53).toDouble()
        }
        var t = 1_000_000L
        for (i in 0 until 400) {
            t += 250_000 + (rnd() * 100_000).toLong()
            val trueOffset = 1e9 + 50e-6 * t
            val maxError = 300 + (rnd() * 1500).toLong()
            var noise = (rnd() * 2 - 1) * maxError * 0.8
            if (i % 97 == 50) noise += 20_000
            f.update((trueOffset + noise).toLong(), maxError, t)
            expected[i]?.let { (server, client) ->
                val map = f.snapshot()
                assertThat(map.serverTime(t + 100_000)).isEqualTo(server)
                assertThat(map.clientTime(3_000_000_000L)).isEqualTo(client)
            }
        }
    }

    @Test
    fun `a measurement from the past or without an error is left out`() {
        val f = TimeFilter()
        assertThat(f.update(500, 100, 1_000)).isTrue()
        assertThat(f.update(500, 100, 1_000)).isFalse()
        assertThat(f.update(500, 0, 2_000)).isFalse()
        assertThat(f.update(500, -5, 3_000)).isFalse()
        assertThat(f.count).isEqualTo(1)
    }

    @Test
    fun `an exchange gives the offset and half the round trip`() {
        // Sent at 1000 here to a clock 1 s ahead, 100 µs on the way each time, answered 100 µs later.
        val m = TimeFilter.Measurement.of(t1 = 1_000, t2 = 1_001_100, t3 = 1_001_200, t4 = 1_300)
        assertThat(m.offset).isEqualTo(1_000_000)
        assertThat(m.maxError).isEqualTo(100)
    }

    @Test
    fun `a clock drifting by 100 ppm is followed within a millisecond`() {
        val f = TimeFilter()
        var t = 0L
        repeat(300) {
            t += 1_000_000
            val offset = 7_000_000_000L + (100e-6 * t).toLong()
            f.update(offset + (if (it % 2 == 0) 400 else -400), 2_000, t)
        }
        val map = f.snapshot()
        val later = t + 5_000_000
        val trueServer = later + 7_000_000_000L + (100e-6 * later).toLong()
        assertThat(abs(map.serverTime(later) - trueServer)).isLessThan(1_000L)
        assertThat(abs(map.clientTime(trueServer) - later)).isLessThan(1_000L)
    }

    @Test
    fun `a reset forgets every measurement`() {
        val f = TimeFilter()
        f.update(1_000, 50, 10)
        f.update(1_100, 50, 20)
        f.reset()
        assertThat(f.count).isEqualTo(0)
        assertThat(f.errorUs).isEqualTo(Double.POSITIVE_INFINITY)
        assertThat(f.update(9, 50, 5)).isTrue()
    }
}
