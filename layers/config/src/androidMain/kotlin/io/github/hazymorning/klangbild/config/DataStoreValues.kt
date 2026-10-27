package io.github.hazymorning.klangbild.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The settings on disk, opened once per process ([DataStoreValues.open]). */
fun settingsValues(context: Context): ValueStore = DataStoreValues.open(context, "settings")

/** Values in a DataStore of preferences, by their names. */
class DataStoreValues(private val store: DataStore<Preferences>) : ValueStore {
    /** How many edits are done: each one makes every watcher read the store anew. */
    private val edits = MutableStateFlow(0)

    // DataStore counts a write before it writes the file, so a watcher whose first read comes
    // in between takes the old values for the new ones and lets the write pass by. Read anew
    // once a write is done, it has them.
    @OptIn(ExperimentalCoroutinesApi::class)
    override val data: Flow<Map<String, Any>> = edits.flatMapLatest { store.data }.map(::named).distinctUntilChanged()

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
        edits.update { it + 1 }
    }

    private fun named(p: Preferences): Map<String, Any> = p.asMap().mapKeys { it.key.name }

    companion object {
        /**
         * The values in the app's preferences file [name], where `preferencesDataStore` keeps it.
         * A file that cannot be read starts empty instead of failing every read. DataStore refuses
         * a second store on a file in use, so each file is opened once per process.
         */
        fun open(context: Context, name: String) = DataStoreValues(
            PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }) {
                context.applicationContext.preferencesDataStoreFile(name)
            },
        )
    }
}
