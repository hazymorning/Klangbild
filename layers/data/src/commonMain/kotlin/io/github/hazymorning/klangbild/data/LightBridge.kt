package io.github.hazymorning.klangbild.data

import io.github.hazymorning.klangbild.domain.EntityState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** The lights as Home Assistant has them, and what is done with them: service calls. */
interface LightBridge {
    /** The states of the lights asked for with [follow]. */
    val lights: StateFlow<Map<String, EntityState>>

    /** Which lights to keep up with. */
    fun follow(ids: Set<String>)

    /** `<domain>.<service>` (for [entityId], if given); the call's context id, null when it failed. */
    suspend fun call(domain: String, service: String, entityId: String?, data: JsonObject?): String?
}
