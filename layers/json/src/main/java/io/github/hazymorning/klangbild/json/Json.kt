package io.github.hazymorning.klangbild.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** This element's text if it is a string; null for anything else (a number, an object, nothing). */
fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The string under [key]; null where there is none or it is not a string. */
fun JsonObject.string(key: String): String? = get(key).string()
