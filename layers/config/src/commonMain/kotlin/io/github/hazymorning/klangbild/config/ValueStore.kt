package io.github.hazymorning.klangbild.config

import kotlinx.coroutines.flow.Flow

/**
 * Values kept on disk by name (texts, whole numbers and switches), as the platform keeps them.
 * The settings have one, and so does the speaker's memory.
 */
interface ValueStore {
    /** What is stored, each time it changes. */
    val data: Flow<Map<String, Any>>

    /**
     * Changes what is stored in one step: [change] gets it as it is and edits it in place.
     * Nothing is written when nothing changed.
     */
    suspend fun edit(change: (MutableMap<String, Any>) -> Unit)
}

/** A stored value's name, and the type it is stored with. */
class StoreKey<T : Any>(val name: String)

fun stringKey(name: String) = StoreKey<String>(name)

fun intKey(name: String) = StoreKey<Int>(name)

fun booleanKey(name: String) = StoreKey<Boolean>(name)

@Suppress("UNCHECKED_CAST") // A name is only ever stored with its key's type.
operator fun <T : Any> Map<String, Any>.get(key: StoreKey<T>): T? = this[key.name] as T?

operator fun <T : Any> MutableMap<String, Any>.set(key: StoreKey<T>, value: T) {
    this[key.name] = value
}

operator fun Map<String, Any>.contains(key: StoreKey<*>): Boolean = key.name in this

fun MutableMap<String, Any>.remove(key: StoreKey<*>) {
    remove(key.name)
}
