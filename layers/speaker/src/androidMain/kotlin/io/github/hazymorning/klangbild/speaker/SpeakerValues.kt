package io.github.hazymorning.klangbild.speaker

import android.content.Context
import io.github.hazymorning.klangbild.config.DataStoreValues
import io.github.hazymorning.klangbild.config.ValueStore

/** The speaker's memory on disk ([SpeakerStore]), opened once per process ([DataStoreValues.open]). */
fun speakerValues(context: Context): ValueStore = DataStoreValues.open(context, "speaker")
