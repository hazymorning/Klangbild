package io.github.hazymorning.klangbild.config

import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test

/**
 * DataStore counts a write before it writes the file, so a reader that starts meanwhile takes the
 * old values for the new ones and lets the write pass by. Whoever watches [DataStoreValues] still
 * sees what was written.
 */
class DataStoreValuesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Writes as DataStore does, but once [held] a write waits between being counted and reaching the file. */
    private class HeldWrites : OkioSerializer<Preferences> {
        @Volatile var held = false
        val writing = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()

        override val defaultValue: Preferences get() = PreferencesSerializer.defaultValue

        override suspend fun readFrom(source: BufferedSource): Preferences = PreferencesSerializer.readFrom(source)

        override suspend fun writeTo(t: Preferences, sink: BufferedSink) {
            if (held) {
                writing.complete(Unit)
                go.await()
            }
            PreferencesSerializer.writeTo(t, sink)
        }
    }

    @Test
    fun `a watcher that starts during a write sees what was written`(): Unit = runBlocking {
        val writes = HeldWrites()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val values = DataStoreValues(
                PreferenceDataStoreFactory.create(
                    storage = OkioStorage(FileSystem.SYSTEM, writes, producePath = { folder.root.toOkioPath() / "values.preferences_pb" }),
                    scope = scope,
                ),
            )
            values.edit { it["name"] = "old" }
            writes.held = true
            val write = scope.launch { values.edit { it["name"] = "new" } }
            writes.writing.await()

            val seen = Channel<Any?>(Channel.UNLIMITED)
            val watcher = scope.launch { values.data.collect { seen.send(it["name"]) } }
            // The watcher's first read comes while the write is counted but not yet in the file.
            assertThat(seen.receive()).isEqualTo("old")
            writes.go.complete(Unit)
            write.join()

            val last = withTimeoutOrNull(5_000) {
                var name = seen.receive()
                while (name != "new") name = seen.receive()
                name
            }
            watcher.cancel()
            assertThat(last).isEqualTo("new")
        } finally {
            scope.cancel()
        }
    }
}
