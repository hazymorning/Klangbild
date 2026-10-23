package io.github.hazymorning.klangbild.landscape

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import io.github.hazymorning.klangbild.text.testZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test

class WorldTest {
    private val berlin = Place(52.52, 13.40)
    private val zone = testZone("Europe/Berlin")

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0, z: TimeZone = zone) =
        LocalDateTime(2026, month, day, hour, minute, 0, 0).toInstant(z).toEpochMilliseconds()

    @Test
    fun `the sun stands in the south at midday, high in June and low in December`() {
        // Solar noon in Berlin is about 13:07 in summer time, 12:07 in winter.
        val june = World.heavens(at(6, 21, 13, 7), berlin)
        assertThat(june.sunAzimuth).isCloseTo(180f, 5f)
        assertThat(june.sunElevation).isCloseTo(61f, 1.5f)
        val december = World.heavens(at(12, 21, 12, 7), berlin)
        assertThat(december.sunAzimuth).isCloseTo(180f, 5f)
        assertThat(december.sunElevation).isCloseTo(14f, 1.5f)
        // It rises in the east and sets in the west.
        assertThat(World.heavens(at(6, 21, 6, 0), berlin).sunAzimuth).isLessThan(100f)
        assertThat(World.heavens(at(6, 21, 20, 30), berlin).sunAzimuth).isGreaterThan(280f)
    }

    @Test
    fun `the moon's phase follows the calendar`() {
        // Full moon on October 26, 2026, new moon on November 9, 2026 (UTC dates).
        assertThat(World.heavens(at(10, 26, 6), berlin).moonFraction).isGreaterThan(0.95f)
        assertThat(World.heavens(at(11, 9, 12), berlin).moonFraction).isLessThan(0.05f)
    }

    @Test
    fun `the stars turn once a sidereal day`() {
        val t = at(10, 10, 22)
        val a = World.siderealDegrees(t, 13.4)
        val b = World.siderealDegrees(t + 86_164_091L, 13.4)
        assertThat(kotlin.math.abs(a - b)).isLessThan(0.05)
    }

    @Test
    fun `the seasons follow the calendar, half a year apart in the south`() {
        fun season(month: Int, day: Int, lat: Double = 52.5) = World.season(at(month, day, 12), zone, lat).first
        assertThat(season(1, 15).winter).isGreaterThan(0.9f)
        assertThat(season(4, 25).spring).isGreaterThan(0.6f)
        assertThat(season(7, 15).summer).isGreaterThan(0.9f)
        assertThat(season(10, 20).autumn).isGreaterThan(0.9f)
        assertThat(season(1, 15, lat = -34.0).summer).isGreaterThan(0.9f)
        // The four always add up to one.
        for (d in 0 until 365) {
            val (m, _) = World.seasonOnDay(d.toFloat())
            assertThat(m.spring + m.summer + m.autumn + m.winter).isCloseTo(1f, 1e-3f)
        }
    }

    @Test
    fun `snow, leaves and blossom come and go over weeks, never overnight`() {
        var prev = World.seasonOnDay(0f).second
        for (d in 1..730) {
            val s = World.seasonOnDay(d / 2f).second
            for ((a, b) in listOf(prev.snow to s.snow, prev.leaves to s.leaves, prev.blossom to s.blossom, prev.peaks to s.peaks)) {
                assertThat(kotlin.math.abs(a - b)).isLessThan(0.06f)
            }
            prev = s
        }
        val july = World.seasonOnDay(195f).second
        assertThat(july.snow).isEqualTo(0f)
        assertThat(july.leaves).isEqualTo(1f)
        val january = World.seasonOnDay(15f).second
        assertThat(january.snow).isGreaterThan(0.9f)
        assertThat(january.leaves).isEqualTo(0f)
    }

    @Test
    fun `without Home Assistant the place comes from the time zone`() {
        assertThat(World.guessPlace(testZone("Europe/Berlin"))).isEqualTo(berlin)
        val tokyo = World.guessPlace(testZone("Asia/Tokyo"))
        assertThat(tokyo.longitude).isCloseTo(135.0, 0.1)
    }

    @Test
    fun `the weather as the picture shows it`() {
        assertThat(WeatherMood.of("sunny", null)!!.cover).isLessThan(0.1f)
        assertThat(WeatherMood.of("partlycloudy", null)!!.cover).isCloseTo(0.45f, 0.01f)
        WeatherMood.of("rainy", null)!!.let {
            assertThat(it.cover).isEqualTo(1f)
            assertThat(it.rain).isGreaterThan(0.3f)
            assertThat(it.snow).isEqualTo(0f)
        }
        assertThat(WeatherMood.of("snowy", null)!!.snow).isGreaterThan(0.3f)
        assertThat(WeatherMood.of("fog", null)!!.fog).isEqualTo(1f)
        assertThat(WeatherMood.of("lightning-rainy", null)!!.storm).isEqualTo(1f)
        assertThat(WeatherMood.of("cloudy", 40.0)!!.cover).isCloseTo(0.64f, 0.01f)
        assertThat(WeatherMood.of(null, 20.0)!!.cover).isCloseTo(0.2f, 0.01f)
        assertThat(WeatherMood.of("unknown", null)).isNull()
    }

    @Test
    fun `the wind as Home Assistant measures it`() {
        assertThat(WeatherMood.of("sunny", null, windKmh = 2.0)!!.wind).isLessThan(0.15f)
        assertThat(WeatherMood.of("sunny", null, windKmh = 40.0)!!.wind).isCloseTo(0.6f, 0.05f)
        assertThat(WeatherMood.of("windy", null, windKmh = 10.0)!!.wind).isGreaterThanOrEqualTo(0.6f)
    }

    @Test
    fun `the weather shown glides toward the weather outside and never jumps`() {
        val rain = WeatherMood.of("rainy", null)!!
        val glide = WeatherGlide.still(Weather.FAIR).toward(rain, 1_000L)
        assertThat(glide.at(1_000L)).isEqualTo(Weather.FAIR)
        // About two thirds of the way after its time constant, all of it after a quarter of an hour.
        assertThat(glide.at(151_000L).rain).isCloseTo(rain.rain * 0.63f, 0.01f)
        assertThat(glide.at(901_000L).distance(rain)).isLessThan(Weather.LIGHT_STEP)
        // A change on the way goes on from where the glide is; the same weather outside changes nothing.
        assertThat(glide.toward(Weather.FAIR, 61_000L).at(61_000L)).isEqualTo(glide.at(61_000L))
        assertThat(glide.toward(rain, 500_000L)).isSameInstanceAs(glide)
    }
}
