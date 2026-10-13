package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.domain.Condition
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The buttons as Klangbild keeps them: a small JSON list of names, entities, variables and conditions. */
object QuickActions {
    /** At most this many buttons: the quick settings stay a short row. */
    const val MAX = 6

    /** The longest name a button may have (characters). */
    const val MAX_NAME = 32

    /** At most this many variables for a script, and this much of them as text. */
    const val MAX_VARIABLES = 16
    const val MAX_VARIABLES_TEXT = 4_000

    /** A variable's name as Home Assistant's scripts take them. */
    private val VARIABLE = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    fun isVariableName(s: String): Boolean = VARIABLE.matches(s)

    /** [actions] as text to store. */
    fun encode(actions: List<QuickAction>): String = buildJsonArray {
        for (a in actions) add(toJson(a))
    }.toString()

    /** One button as the store and the setup page write it. */
    fun toJson(a: QuickAction): JsonObject = buildJsonObject {
        put("name", a.name)
        put("entity", a.entity)
        if (a.variables.isNotEmpty()) put("variables", a.variables)
        a.condition?.let { put("when", it.toJson()) }
    }

    /** The buttons stored as [text]; whatever cannot be read is left out. */
    fun decode(text: String?): List<QuickAction> {
        if (text.isNullOrBlank()) return emptyList()
        val list = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { from(it as? JsonObject) }.take(MAX)
    }

    /** A button from what the phone sent or what is stored; null when it is none. */
    fun from(o: JsonObject?): QuickAction? {
        o ?: return null
        val name = o.string("name")
        val entity = o.string("entity")
        val variables = when (val v = o["variables"]) {
            null -> QuickAction.NO_VARIABLES
            is JsonObject -> v
            else -> return null
        }
        val condition = when (val w = o["when"]) {
            null -> null
            is JsonObject -> Condition.from(w) ?: return null
            else -> return null
        }
        return valid(name, entity, variables, condition)
    }

    /**
     * A button from what the phone sent: an entity a button can work, and a name (cut to
     * [MAX_NAME] characters; without one, the entity's own words). Only a script takes
     * variables. Null when it is no button.
     */
    fun valid(name: String?, entity: String?, variables: JsonObject = QuickAction.NO_VARIABLES, condition: Condition? = null): QuickAction? {
        val id = entity?.trim()?.takeIf { EntityId.isValid(it) && ActionKind.of(it) != null } ?: return null
        val words = name?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: nameOf(id)
        val script = id.startsWith("script.")
        if (script && (variables.size > MAX_VARIABLES || variables.keys.any { !isVariableName(it) } || variables.toString().length > MAX_VARIABLES_TEXT)) return null
        return QuickAction(words.take(MAX_NAME).trimEnd(), id, if (script) variables else QuickAction.NO_VARIABLES, condition)
    }

    /** An entity's own words: `script.ungroup_speakers` is "Ungroup speakers". */
    fun nameOf(entity: String): String =
        entity.substringAfter('.').replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

    /** The entities the buttons need followed: their own, and those their conditions look at. */
    fun entities(actions: List<QuickAction>): Set<String> =
        actions.flatMapTo(LinkedHashSet()) { listOfNotNull(it.entity, it.condition?.entity) }
}
