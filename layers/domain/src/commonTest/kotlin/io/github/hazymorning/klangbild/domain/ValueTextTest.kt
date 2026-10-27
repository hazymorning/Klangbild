package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.text.testZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/** A custom value as text: Home Assistant's decimals and units, the language's figures, nothing for what cannot be told. */
class ValueTextTest {
    private val en = TestTexts.english()
    private val de = TestTexts.german
    private val zone = testZone("Europe/Berlin")
    private val now = LocalDateTime(2026, 10, 11, 20, 0).toInstant(zone).toEpochMilliseconds()

    private fun text(
        state: String?,
        attrs: String = "{}",
        name: String = "",
        attribute: String? = null,
        entity: String = "sensor.x",
        precision: Int? = null,
        unit: String? = null,
        t: Texts = en,
    ) = ValueText.of(OwnValue(name, entity, attribute), state, Json.parseToJsonElement(attrs).jsonObject, precision, unit, t, now, zone)

    @Test
    fun `numbers keep Home Assistant's decimals and their unit`() {
        val c = """{"unit_of_measurement":"°C"}"""
        assertThat(text("12.345", c, precision = 1)).isEqualTo("12.3 °C")
        assertThat(text("12.345", c, precision = 1, t = de)).isEqualTo("12,3 °C")
        // Without a precision of its own, as many decimals as the state has.
        assertThat(text("12.30", c, t = de)).isEqualTo("12,30 °C")
        assertThat(text("1234.5", """{"unit_of_measurement":"W"}""", precision = 0)).isEqualTo("1,235 W")
        assertThat(text("1234.5", """{"unit_of_measurement":"W"}""", precision = 1, t = de)).isEqualTo("1.234,5 W")
        // A percentage as the language writes it.
        assertThat(text("35", """{"unit_of_measurement":"%"}""")).isEqualTo("35%")
        assertThat(text("35", """{"unit_of_measurement":"%"}""", t = de)).isEqualTo("35 %")
        assertThat(text("-3", """{"unit_of_measurement":"°"}""")).isEqualTo("-3°")
    }

    @Test
    fun `a name goes before the value`() {
        assertThat(text("12.3", """{"unit_of_measurement":"°C"}""", name = "Outside")).isEqualTo("Outside 12.3 °C")
        assertThat(text("Washing", name = "Machine")).isEqualTo("Machine Washing")
    }

    @Test
    fun `on and off in words, a time as a time of day, other words as they are`() {
        assertThat(text("on")).isEqualTo("On")
        assertThat(text("off", t = de)).isEqualTo("Aus")
        val ts = """{"device_class":"timestamp"}"""
        assertThat(text("2026-10-11T19:30:00+00:00", ts, t = de)).isEqualTo("21:30")
        assertThat(text("2026-10-20T19:30:00+00:00", ts)).isNotNull().doesNotContain(":")
        assertThat(text("Ready to unload")).isEqualTo("Ready to unload")
    }

    @Test
    fun `what Home Assistant cannot tell shows nothing`() {
        for (s in listOf("unavailable", "unknown", "", "  ")) assertThat(text(s)).isNull()
        assertThat(text(null)).isNull()
        assertThat(text("on", attribute = "missing")).isNull()
        assertThat(text("on", """{"list":[1,2]}""", attribute = "list")).isNull()
    }

    @Test
    fun `an attribute takes its unit where Home Assistant names it`() {
        val weather = """{"temperature":12.3,"temperature_unit":"°C","humidity":80,"wind_speed":14.8,"wind_speed_unit":"km/h"}"""
        assertThat(text("cloudy", weather, attribute = "temperature", entity = "weather.home", t = de)).isEqualTo("12,3 °C")
        assertThat(text("cloudy", weather, attribute = "humidity", entity = "weather.home")).isEqualTo("80%")
        assertThat(text("cloudy", weather, attribute = "wind_speed", entity = "weather.home")).isEqualTo("14.8 km/h")
        val climate = """{"current_temperature":21.5,"hvac_action":"heating","preset":true}"""
        assertThat(text("heat", climate, attribute = "current_temperature", entity = "climate.living", unit = "°C")).isEqualTo("21.5 °C")
        assertThat(text("heat", climate, attribute = "hvac_action", entity = "climate.living")).isEqualTo("heating")
        assertThat(text("heat", climate, attribute = "preset", entity = "climate.living")).isEqualTo("On")
        // The sensor's own precision is the state's, not its attributes'.
        assertThat(text("5", """{"level":3.14159}""", attribute = "level", precision = 0)).isEqualTo("3.14159")
    }
}
