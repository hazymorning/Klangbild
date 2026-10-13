package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.domain.EntityState

/**
 * The states Klangbild follows, as far as they have arrived: from the REST API when a
 * connection starts, from state events after that. A state never replaces a newer one, so a
 * slow REST answer cannot undo an event that overtook it.
 */
class EntityStore {
    private val states = LinkedHashMap<String, EntityState>()

    fun clear() = states.clear()

    operator fun get(entityId: String): EntityState? = states[entityId]

    fun snapshot(): Map<String, EntityState> = LinkedHashMap(states)

    /** Takes [state] unless a newer one is known; whether it changed anything. */
    fun put(state: EntityState): Boolean {
        val known = states[state.entityId]
        if (known != null && known.lastUpdated > state.lastUpdated) return false
        if (known == state) return false
        states[state.entityId] = state
        return true
    }

    /** Forgets [entityId] (it was removed from Home Assistant, or is no longer followed). */
    fun remove(entityId: String): Boolean = states.remove(entityId) != null

    /** Keeps only the entities in [ids]; whether any left. */
    fun retain(ids: Set<String>): Boolean = states.keys.retainAll(ids)
}
