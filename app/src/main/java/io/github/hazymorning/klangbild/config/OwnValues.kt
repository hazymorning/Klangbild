package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.Condition
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.OwnValue
import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The values as Klangbild keeps them: a small JSON list of names, entities and attributes. */
object OwnValues {
    /** At most this many: the status line is one short line. */
    const val MAX = 3

    /** The longest name a value may have (characters). */
    const val MAX_NAME = 24

    fun encode(values: List<OwnValue>): String = buildJsonArray {
        for (v in values) add(toJson(v))
    }.toString()

    fun toJson(v: OwnValue): JsonObject = buildJsonObject {
        put("name", v.name)
        put("entity", v.entity)
        if (v.attribute != null) put("attribute", v.attribute)
    }

    /** The values stored as [text]; whatever cannot be read is left out. */
    fun decode(text: String?): List<OwnValue> {
        if (text.isNullOrBlank()) return emptyList()
        val list = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { from(it as? JsonObject) }.take(MAX)
    }

    /** A value from what the phone sent or what is stored: any entity, a name cut to [MAX_NAME]; null when it is none. */
    fun from(o: JsonObject?): OwnValue? {
        o ?: return null
        fun str(key: String) = o.string(key)?.trim()
        val entity = str("entity")?.takeIf { EntityId.isValid(it) } ?: return null
        val attribute = str("attribute")?.takeIf { it.isNotEmpty() }
        if (attribute != null && !Condition.ATTRIBUTE.matches(attribute)) return null
        val name = str("name").orEmpty().replace(Regex("\\s+"), " ").take(MAX_NAME).trimEnd()
        return OwnValue(name, entity, attribute)
    }
}
