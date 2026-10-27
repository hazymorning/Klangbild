package io.github.hazymorning.klangbild.domain

/**
 * A custom value for the status line, set up on the phone: an entity's state or one of its
 * attributes, after an optional [name] ("Outside 12 °C").
 */
data class OwnValue(val name: String, val entity: String, val attribute: String? = null)
