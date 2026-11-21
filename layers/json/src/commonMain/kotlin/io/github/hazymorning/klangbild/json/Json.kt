package io.github.hazymorning.klangbild.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** This element's text if it is a string; null for anything else (a number, an object, nothing). */
fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The string under [key]; null where there is none or it is not a string. */
fun JsonObject.string(key: String): String? = get(key).string()

/** This element as a whole number, also when a string holds it ("5"); null for anything else, a fraction too. */
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

/** This element as a whole number that may be large, also when a string holds it; null for anything else. */
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

/** This element as a number, also when a string holds it ("1.5"); null for anything else. */
fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/** This element as true or false, also when a string holds it ("true"); null for anything else. */
fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** The whole number under [key]; null where there is none or it is not one. */
fun JsonObject.int(key: String): Int? = get(key).int()

/** The whole number under [key] that may be large; null where there is none or it is not one. */
fun JsonObject.long(key: String): Long? = get(key).long()

/** The number under [key]; null where there is none or it is not one. */
fun JsonObject.double(key: String): Double? = get(key).double()

/** True or false under [key]; null where there is neither. */
fun JsonObject.boolean(key: String): Boolean? = get(key).boolean()
