package io.github.hazymorning.klangbild.domain

import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.time.Clock

/**
 * A custom value as the status line shows it ("Outside 12.5 °C"): the entity's state, or
 * one of its attributes, after the value's name. Numbers in the language's figures with Home
 * Assistant's decimals (its display precision, else as many as the state has) and their unit,
 * on and off in words, a time as a time of day. Anything else shows as Home Assistant says it.
 * What Home Assistant cannot tell (unavailable, unknown, nothing) shows nothing, so a template
 * helper can hide a value by having none.
 */
object ValueText {
    fun of(
        value: OwnValue,
        state: String?,
        attributes: JsonObject?,
        precision: Int?,
        temperatureUnit: String?,
        t: Texts,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String? {
        state ?: return null
        val attribute = value.attribute
        val text = if (attribute == null) {
            val timestamp = attributes?.string("device_class") == "timestamp"
            format(JsonPrimitive(state), attributes?.string("unit_of_measurement"), precision, timestamp, t, nowMs, zone)
        } else {
            val raw = attributes?.get(attribute) as? JsonPrimitive ?: return null
            format(raw, unitOf(value.entity, attribute, attributes, temperatureUnit), null, timestamp = false, t, nowMs, zone)
        } ?: return null
        return if (value.name.isEmpty()) text else "${value.name} $text"
    }

    /** A state or an attribute's value as the status line shows it. */
    private fun format(p: JsonPrimitive, unit: String?, precision: Int?, timestamp: Boolean, t: Texts, nowMs: Long, zone: TimeZone): String? {
        if (!p.isString) {
            p.booleanOrNull?.let { return t[if (it) Strings.on else Strings.off] }
            val n = p.doubleOrNull ?: return null
            return withUnit(t.number(n, precision ?: decimalsOf(p.content)), unit, t)
        }
        val s = p.content.trim()
        if (s.isEmpty() || s == "unavailable" || s == "unknown") return null
        if (timestamp) {
            val at = EntityState.instant(s)?.toEpochMilliseconds()
            if (at != null) return if (abs(at - nowMs) < DAY_MS) t.clock(at, zone) else t.date(at, zone)
        }
        if (s == "on" || s == "off") return t[if (s == "on") Strings.on else Strings.off]
        val n = s.toDoubleOrNull()?.takeIf { it.isFinite() && NUMBER.matches(s) }
            ?: return Texts.fontSafe(s)
        return withUnit(t.number(n, precision ?: decimalsOf(s)), unit, t)
    }

    /** "12.5 °C", "35 %" or "35%" as the language writes a percentage, "20 min". */
    private fun withUnit(number: String, unit: String?, t: Texts): String = when {
        unit.isNullOrBlank() -> number
        unit == "°" -> number + unit
        unit == "%" -> number + t.percentSuffix
        else -> "$number\u00A0$unit"
    }

    /** The decimals a number is written with ("12.30" has two), at most six. */
    private fun decimalsOf(s: String): Int = s.substringAfter('.', "").takeWhile { it.isDigit() }.length.coerceAtMost(6)

    /** The unit of an attribute where Home Assistant names it: a weather entity's own units, a thermostat's temperatures. */
    private fun unitOf(entity: String, attribute: String, attributes: JsonObject?, temperatureUnit: String?): String? {
        fun own(key: String) = attributes?.string(key)
        return when (entity.substringBefore('.')) {
            "weather" -> when (attribute) {
                "temperature", "apparent_temperature", "dew_point" -> own("temperature_unit")
                "pressure" -> own("pressure_unit")
                "wind_speed", "wind_gust_speed" -> own("wind_speed_unit")
                "visibility" -> own("visibility_unit")
                "precipitation" -> own("precipitation_unit")
                "humidity", "cloud_coverage" -> "%"
                else -> null
            }
            "climate", "water_heater" -> when (attribute) {
                "current_temperature", "temperature", "target_temp_high", "target_temp_low" -> temperatureUnit
                "current_humidity", "humidity" -> "%"
                else -> null
            }
            else -> null
        }
    }

    /** A number as Home Assistant writes a state: digits, a point, maybe a sign and an exponent. */
    private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")

    private const val DAY_MS = 24 * 3_600_000L
}
