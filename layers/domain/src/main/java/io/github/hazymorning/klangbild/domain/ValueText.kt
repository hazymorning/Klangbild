package io.github.hazymorning.klangbild.domain

import io.github.hazymorning.klangbild.text.R
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * A value of one's own as the status line shows it ("Outside 12.5 °C"): the entity's state, or
 * one of its attributes, after the value's name. Numbers in the language's figures with Home
 * Assistant's decimals (its display precision, else as many as the state has) and their unit,
 * on and off in words, a time as a time of day. Anything else stands as Home Assistant says it.
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
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        state ?: return null
        val attribute = value.attribute
        val raw: JsonElement = if (attribute == null) JsonPrimitive(state) else attributes?.get(attribute) ?: return null
        val text = (raw as? JsonPrimitive)?.let { p ->
            val unit = if (attribute == null) (attributes?.get("unit_of_measurement") as? JsonPrimitive)?.contentOrNull else unitOf(value.entity, attribute, attributes, temperatureUnit)
            val timestamp = attribute == null && (attributes?.get("device_class") as? JsonPrimitive)?.contentOrNull == "timestamp"
            word(p, unit, if (attribute == null) precision else null, timestamp, t, nowMs, zone)
        } ?: return null
        return if (value.name.isEmpty()) text else "${value.name} $text"
    }

    private fun word(p: JsonPrimitive, unit: String?, precision: Int?, timestamp: Boolean, t: Texts, nowMs: Long, zone: ZoneId): String? {
        if (!p.isString) {
            p.booleanOrNull?.let { return t[if (it) R.string.on else R.string.off] }
            val n = p.doubleOrNull ?: return null
            return withUnit(t.number(n, precision ?: decimalsOf(p.content)), unit, t)
        }
        val s = p.content.trim()
        if (s.isEmpty() || s == "unavailable" || s == "unknown") return null
        if (timestamp) {
            val at = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            if (at != null) return if (abs(at.toEpochMilli() - nowMs) < DAY_MS) t.clock(at.toEpochMilli(), zone) else t.date(at.toEpochMilli(), zone)
        }
        if (s == "on" || s == "off") return t[if (s == "on") R.string.on else R.string.off]
        val n = s.toDoubleOrNull()?.takeIf { it.isFinite() && NUMBER.matches(s) }
            ?: return Texts.fontSafe(s)
        return withUnit(t.number(n, precision ?: decimalsOf(s)), unit, t)
    }

    /** "12.5 °C", "35 %" or "35%" as the language writes a percentage, "20 min". */
    private fun withUnit(number: String, unit: String?, t: Texts): String = when {
        unit.isNullOrBlank() -> number
        unit == "°" -> number + unit
        unit == "%" -> if (t.percent(0).contains(' ')) "$number %" else "$number%"
        else -> "$number $unit"
    }

    /** The decimals a number is written with ("12.30" has two), at most six. */
    private fun decimalsOf(s: String): Int = s.substringAfter('.', "").takeWhile { it.isDigit() }.length.coerceAtMost(6)

    /** The unit of an attribute where Home Assistant names it: a weather entity's own units, a thermostat's temperatures. */
    private fun unitOf(entity: String, attribute: String, attributes: JsonObject?, temperatureUnit: String?): String? {
        fun own(key: String) = (attributes?.get(key) as? JsonPrimitive)?.contentOrNull
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
