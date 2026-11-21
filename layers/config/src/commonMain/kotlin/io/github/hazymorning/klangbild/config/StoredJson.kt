package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

// What the values of one's own, the buttons and their conditions share as Klangbild keeps them
// and the phone sends them.

/** [items] as a JSON list, each one as [write] has it. */
internal fun <T> encodeList(items: List<T>, write: (T) -> JsonObject): String = buildJsonArray {
    for (item in items) add(write(item))
}.toString()

/** The list stored as [text], at most [max] entries, each one as [read] takes it; whatever cannot be read is left out. */
internal fun <T : Any> decodeList(text: String?, max: Int, read: (JsonObject?) -> T?): List<T> {
    if (text.isNullOrBlank()) return emptyList()
    val list = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
    return list.mapNotNull { read(it as? JsonObject) }.take(max)
}

/** Puts [entity], and its [attribute] if there is one. */
internal fun JsonObjectBuilder.putEntity(entity: String, attribute: String?) {
    put("entity", entity)
    if (attribute != null) put("attribute", attribute)
}

/** The entity [o] names and the attribute of it, if any; null when either can't be one. */
internal fun entityIn(o: JsonObject): Pair<String, String?>? {
    val entity = o.string("entity")?.trim()?.takeIf { EntityId.isValid(it) } ?: return null
    val attribute = o.string("attribute")?.trim()?.takeIf { it.isNotEmpty() }
    if (attribute != null && !EntityId.isAttribute(attribute)) return null
    return entity to attribute
}

/** [name] in plain words: on one line, with single spaces, cut to [max] characters. */
internal fun plainName(name: String?, max: Int): String = name.orEmpty().trim().replace(Regex("\\s+"), " ").take(max).trimEnd()
