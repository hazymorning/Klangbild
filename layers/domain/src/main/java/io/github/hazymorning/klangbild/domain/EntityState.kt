package io.github.hazymorning.klangbild.domain

import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One entity as received from Home Assistant. Times are epoch seconds. [context] is the id of
 * what made the last change (a service call's, or the device's own report); [contextParent]
 * and [contextUser] say whether an automation or a person in Home Assistant made it.
 */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: Double,
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
            val changed = seconds(o.string("last_changed")) ?: 0.0
            val context = o["context"] as? JsonObject
            return EntityState(
                entityId = id,
                state = o.string("state") ?: "unknown",
                attributes = o["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = changed,
                lastUpdated = seconds(o.string("last_updated")) ?: changed,
                context = context?.string("id"),
                contextParent = context?.string("parent_id"),
                contextUser = context?.string("user_id"),
            )
        }

        /** An ISO 8601 time as epoch seconds. */
        fun seconds(iso: String?): Double? = try {
            iso?.let { OffsetDateTime.parse(it).toInstant() }?.let { it.epochSecond + it.nano / 1e9 }
        } catch (_: DateTimeParseException) {
            null
        }

    }
}
