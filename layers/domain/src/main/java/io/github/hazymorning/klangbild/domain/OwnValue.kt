package io.github.hazymorning.klangbild.domain

/**
 * A value of one's own in the status line, set up on the phone: the state of a Home Assistant
 * entity, or one of its attributes, after a [name] of one's own ("Outside 12 °C"); without a
 * name the value stands alone.
 */
data class OwnValue(val name: String, val entity: String, val attribute: String? = null)
