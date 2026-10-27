package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.ValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What a platform keeps, in memory. */
class MemoryValues(initial: Map<String, Any> = emptyMap()) : ValueStore {
    private val values = MutableStateFlow(initial)
    override val data: StateFlow<Map<String, Any>> = values

    override suspend fun edit(change: (MutableMap<String, Any>) -> Unit) {
        values.value = values.value.toMutableMap().also(change)
    }
}
