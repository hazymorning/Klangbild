package io.github.hazymorning.klangbild.domain

/**
 * What a Home Assistant entity id looks like (`media_player.living_room`): a domain, a dot, an
 * object id. Its attributes' names are letters, digits and underscores.
 */
object EntityId {
    private val FORM = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val ATTRIBUTE = Regex("[A-Za-z0-9_]{1,64}")

    fun isValid(s: String): Boolean = FORM.matches(s)

    /** Whether [name] can be an attribute's name. */
    fun isAttribute(name: String): Boolean = ATTRIBUTE.matches(name)

    /** A light (`light.living_room_left`). */
    fun isLight(s: String): Boolean = s.startsWith("light.") && isValid(s)

    /**
     * The speaker chosen as "This TV" on the setup page: this TV's own speaker in Music
     * Assistant, whatever its entity turns out to be. Without a dot, it is no entity id.
     */
    const val THIS_TV = "this_tv"
}
