package io.github.hazymorning.klangbild.speaker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.github.hazymorning.klangbild.config.DataStoreValues
import io.github.hazymorning.klangbild.config.ValueStore

private val Context.speaker: DataStore<Preferences> by preferencesDataStore(name = "speaker")

/** The speaker's memory on disk ([SpeakerStore]). */
fun speakerValues(context: Context): ValueStore = DataStoreValues(context.speaker)
