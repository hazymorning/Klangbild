package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.OwnValue
import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The values as Klangbild keeps them: a small JSON list of names, entities and attributes. */
object OwnValues {
    /** At most this many: the status line is one short line. */
    const val MAX = 3

    /** The longest name a value may have (characters). */
    const val MAX_NAME = 24

    fun encode(values: List<OwnValue>): String = encodeList(values, ::toJson)

    fun toJson(v: OwnValue): JsonObject = buildJsonObject {
        put("name", v.name)
        putEntity(v.entity, v.attribute)
    }

    /** The values stored as [text]; whatever cannot be read is left out. */
    fun decode(text: String?): List<OwnValue> = decodeList(text, MAX, ::from)

    /** A value from what the phone sent or what is stored: any entity, a name cut to [MAX_NAME]; null when it is none. */
    fun from(o: JsonObject?): OwnValue? {
        o ?: return null
        val (entity, attribute) = entityIn(o) ?: return null
        return OwnValue(plainName(o.string("name"), MAX_NAME), entity, attribute)
    }
}
