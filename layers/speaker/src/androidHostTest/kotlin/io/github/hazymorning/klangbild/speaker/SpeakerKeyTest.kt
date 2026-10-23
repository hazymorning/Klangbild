package io.github.hazymorning.klangbild.speaker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.startsWith
import io.github.hazymorning.klangbild.config.DataStoreValues
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.data.AndroidLog
import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.domain.SpeakerLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeakerKeyTest {
    /** Seals by reversing behind a mark; [opening] off plays a keystore that refuses for a while. */
    private class Box : SecretBox {
        @Volatile var opening = true
        override fun seal(plain: String) = "sealed:" + plain.reversed()
        override fun open(stored: String): String? = if (!opening) null else stored.removePrefix("sealed:").reversed()
        override fun isPlain(stored: String) = !stored.startsWith("sealed:")
    }

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a key the keystore cannot open yet is asked for again, and the speaker goes on once it opens`(): Unit = runBlocking {
        val box = Box()
        val store = SpeakerStore(DataStoreValues(PreferenceDataStoreFactory.create { File(folder.root, "speaker.preferences_pb") }), box)
        val key = store.load().identity!!.publicKey
        box.opening = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Nothing answers on the discard port: once the key opens, the speaker only gets as far as connecting.
        val speaker = Speaker(
            scope, speakerClient(OkHttpClient()), store, "ws://127.0.0.1:9/sendspin",
            SpeakerIdentity("Living room TV", "Test box", "Klangbild", "test"),
            sinkFactory = { RealTimeSink() },
            audioThread = AndroidAudioThread(setup = {}),
            sleptUs = { 0 },
            log = AndroidLog,
            keyRetry = { Backoff(20, 20) },
        )
        try {
            speaker.start()
            withTimeout(5_000) { speaker.status.first { it.link == SpeakerLink.NO_KEY } }
            assertThat(speaker.pairingToken).isNull()
            box.opening = true
            val going = withTimeout(5_000) { speaker.status.first { it.link != SpeakerLink.NO_KEY && it.clientId != null } }
            assertThat(going.clientId).isEqualTo(io.github.hazymorning.klangbild.sendspin.B64.encode(key))
            assertThat(speaker.pairingToken).isNotNull().startsWith("SP:0")
        } finally {
            speaker.stop("shutdown")
            scope.cancel()
        }
    }
}
