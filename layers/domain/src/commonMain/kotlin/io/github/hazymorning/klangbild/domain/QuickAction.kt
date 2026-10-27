package io.github.hazymorning.klangbild.domain

import kotlinx.serialization.json.JsonObject

/**
 * A custom button in the quick settings, set up on the phone: its [name] and the Home
 * Assistant entity it controls. What it does follows from the entity's kind ([ActionKind]): a
 * script runs (with [variables], and learns which speaker the TV follows), a scene is activated,
 * an automation runs its actions, a button is pressed, a switch is turned on or off. With a
 * [condition] it shows only while that holds.
 */
data class QuickAction(
    val name: String,
    val entity: String,
    val variables: JsonObject = NO_VARIABLES,
    val condition: Condition? = null,
) {
    val kind: ActionKind? get() = ActionKind.of(entity)

    companion object {
        val NO_VARIABLES = JsonObject(emptyMap())
    }
}

/** What a button does with its entity, by the entity's domain. */
enum class ActionKind(val domains: List<String>) {
    /** Runs a script, or an automation's actions. */
    RUN(listOf("script", "automation")),

    /** Activates a scene. */
    ACTIVATE(listOf("scene")),

    /** Presses a button. */
    PRESS(listOf("button", "input_button")),

    /** Switches something on or off, and shows which it is. */
    TOGGLE(listOf("switch", "input_boolean", "light")),
    ;

    companion object {
        /** The kind for [entity] (`script.ungroup`), null for an entity no button can work. */
        fun of(entity: String): ActionKind? {
            val domain = entity.substringBefore('.', "")
            return entries.firstOrNull { domain in it.domains }
        }

        /** Every domain a button can work, in the order the setup page lists them. */
        val DOMAINS: List<String> = entries.flatMap { it.domains }
    }
}
