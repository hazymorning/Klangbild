package io.github.hazymorning.klangbild.domain

import io.github.hazymorning.klangbild.json.string
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant

/**
 * One entity as received from Home Assistant. Times are epoch seconds. [context] is the id of
 * what made the last change (a service call's, or the device's own report); [contextParent]
 * and [contextUser] say whether an automation or a person in Home Assistant made it.
 */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastUpdated: Double,
    val context: String? = null,
    val contextParent: String? = null,
    val contextUser: String? = null,
) {
    fun attr(name: String): JsonElement? = attributes[name]?.takeUnless { it is JsonNull }
    fun string(name: String): String? = attr(name).string()
    fun number(name: String): Double? = (attr(name) as? JsonPrimitive)?.doubleOrNull

    companion object {
        /**
         * A state object as the REST API and state events carry it (`entity_id`, `state`,
         * `attributes`, `last_changed`, `last_updated`, `context`); null when it is not one.
         */
        fun of(o: JsonObject?): EntityState? {
            o ?: return null
            val id = o.string("entity_id") ?: return null
            val context = o["context"] as? JsonObject
            return EntityState(
                entityId = id,
                state = o.string("state") ?: "unknown",
                attributes = o["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                lastUpdated = seconds(o.string("last_updated")) ?: seconds(o.string("last_changed")) ?: 0.0,
                context = context?.string("id"),
                contextParent = context?.string("parent_id"),
                contextUser = context?.string("user_id"),
            )
        }

        /** An ISO 8601 time as epoch seconds. */
        private fun seconds(iso: String?): Double? = iso?.let(::instant)?.let { it.epochSeconds + it.nanosecondsOfSecond / 1e9 }

        /**
         * An ISO 8601 time with its offset ("2026-10-20T19:30:00.5+02:00", or without the seconds);
         * null when it is not one.
         */
        fun instant(iso: String): Instant? = try {
            DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(iso.replace(NO_SECONDS, "$1:00")).toInstantUsingOffset()
        } catch (_: IllegalArgumentException) {
            null
        }

        /** A time of day written without its seconds, which the format above needs. */
        private val NO_SECONDS = Regex("^(\\d{4}-\\d\\d-\\d\\d[Tt]\\d\\d:\\d\\d)(?=[Zz+-])")
    }
}
