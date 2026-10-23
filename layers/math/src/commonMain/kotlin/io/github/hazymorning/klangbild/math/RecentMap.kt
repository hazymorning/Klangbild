package io.github.hazymorning.klangbild.math

/**
 * A map of the [max] entries used last. Reading or writing an entry makes it the newest, and a
 * new entry beyond [max] drops the oldest, which [dropped] hears of. Asking whether a key is
 * there doesn't count as using it.
 */
class RecentMap<K, V : Any>(private val max: Int, private val dropped: (K, V) -> Unit = { _, _ -> }) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = map.remove(key)?.also { map[key] = it }

    operator fun set(key: K, value: V) {
        val known = map.remove(key) != null
        map[key] = value
        if (!known && map.size > max) {
            val oldest = map.keys.first()
            dropped(oldest, map.remove(oldest)!!)
        }
    }

    operator fun contains(key: K): Boolean = key in map

    fun remove(key: K): V? = map.remove(key)

    fun getOrPut(key: K, value: () -> V): V = get(key) ?: value().also { set(key, it) }

    fun clear() = map.clear()
}
