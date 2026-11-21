package io.github.hazymorning.klangbild.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * When a custom button shows: an entity's state, or one of its attributes, compared with a
 * value, as in Home Assistant's own state conditions. Text compares without regard to case,
 * numbers as numbers, and a list counts its entries ("group_members above 1": the speaker plays
 * in a group). An entity Home Assistant does not have never shows the button; for anything more,
 * a template helper in Home Assistant gives a state to compare.
 */
data class Condition(val entity: String, val attribute: String?, val test: Test, val value: String) {
    enum class Test(val key: String) {
        IS("is"),
        IS_NOT("is_not"),
        ABOVE("above"),
        BELOW("below"),
        ;

        companion object {
            fun of(key: String?): Test? = entries.firstOrNull { it.key == key }
        }
    }

    /** Whether it holds for the entity's [state] and [attributes]; a null [state]: Home Assistant does not have the entity. */
    fun holds(state: String?, attributes: JsonObject?): Boolean {
        if (state == null) return false
        val seen: JsonElement? = if (attribute == null) JsonPrimitive(state) else attributes?.get(attribute)
        return when (test) {
            Test.IS -> seen != null && same(seen)
            Test.IS_NOT -> seen == null || !same(seen)
            Test.ABOVE -> number(seen)?.let { n -> value.toNumber()?.let { n > it } } ?: false
            Test.BELOW -> number(seen)?.let { n -> value.toNumber()?.let { n < it } } ?: false
        }
    }

    private fun same(seen: JsonElement): Boolean {
        val n = number(seen)
        val v = value.toNumber()
        if (n != null && v != null) return n == v
        return text(seen).equals(value.trim(), ignoreCase = true)
    }

    companion object {
        /** [seen] as a number: a number, a number in words ("21.5"), or a list's count. */
        fun number(seen: JsonElement?): Double? = when (seen) {
            is JsonArray -> seen.size.toDouble()
            is JsonPrimitive -> if (seen.isString) seen.content.toNumber() else seen.doubleOrNull
            else -> null
        }

        private fun text(seen: JsonElement): String = when (seen) {
            is JsonNull -> ""
            is JsonPrimitive -> seen.content.trim()
            is JsonArray -> seen.size.toString()
            else -> seen.toString()
        }

        private fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
