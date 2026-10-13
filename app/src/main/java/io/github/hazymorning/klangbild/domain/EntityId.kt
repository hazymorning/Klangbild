package io.github.hazymorning.klangbild.domain

/** What a Home Assistant entity id looks like (`media_player.living_room`): a domain, a dot, an object id. */
object EntityId {
    private val FORM = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

    fun isValid(s: String): Boolean = FORM.matches(s)

    /** A light (`light.living_room_left`). */
    fun isLight(s: String): Boolean = s.startsWith("light.") && isValid(s)

    /** A weather entity (`weather.forecast_home`). */
    fun isWeather(s: String): Boolean = s.startsWith("weather.") && isValid(s)
}
