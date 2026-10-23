package io.github.hazymorning.klangbild.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The settings on disk. */
fun settingsValues(context: Context): ValueStore = DataStoreValues(context.settings)

/** Values in a DataStore of preferences, by their names. */
class DataStoreValues(private val store: DataStore<Preferences>) : ValueStore {
    override val data: Flow<Map<String, Any>> = store.data.map(::named)

    override suspend fun edit(change: (MutableMap<String, Any>) -> Unit) {
        store.edit { p ->
            val before = named(p)
            val after = before.toMutableMap().also(change)
            // A key stands for its name, whatever its type.
            for (name in before.keys - after.keys) p.remove(stringPreferencesKey(name))
            for ((name, value) in after) {
                if (before[name] == value) continue
                when (value) {
                    is String -> p[stringPreferencesKey(name)] = value
                    is Int -> p[intPreferencesKey(name)] = value
                    is Boolean -> p[booleanPreferencesKey(name)] = value
                    else -> throw IllegalArgumentException("$name: no store for ${value::class.simpleName}")
                }
            }
        }
    }

    private fun named(p: Preferences): Map<String, Any> = p.asMap().mapKeys { it.key.name }
}
