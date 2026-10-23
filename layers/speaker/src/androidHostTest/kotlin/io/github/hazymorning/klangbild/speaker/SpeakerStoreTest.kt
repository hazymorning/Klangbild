package io.github.hazymorning.klangbild.speaker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.config.DataStoreValues
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.sendspin.PairingRecord
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeakerStoreTest {
    /**
     * Seals by reversing behind a mark; [opening] off plays a keystore that refuses to open,
     * [sealing] off one that cannot seal (the value is kept as it is).
     */
    private class FakeBox : SecretBox {
        var opening = true
        var sealing = true
        val sealed = mutableListOf<String>()
        override fun seal(plain: String) = if (!sealing) plain else ("sealed:" + plain.reversed()).also { sealed += it }
        override fun open(stored: String): String? = when {
            isPlain(stored) -> stored
            !opening -> null
            else -> stored.removePrefix("sealed:").reversed()
        }
        override fun isPlain(stored: String) = !stored.startsWith("sealed:")
    }

    @get:Rule val folder = TemporaryFolder()

    /** A store of its own for each test, empty at first. */
    private fun storeWith(box: SecretBox) = SpeakerStore(DataStoreValues(PreferenceDataStoreFactory.create { File(folder.root, "speaker.preferences_pb") }), box)

    @Test
    fun `the identity is made once and kept, sealed`(): Unit = runBlocking {
        val box = FakeBox()
        val store = storeWith(box)
        val first = store.load()
        val again = store.load()
        assertThat(first.identity).isNotNull()
        assertThat(again.identity!!.publicKey).isEqualTo(first.identity!!.publicKey)
        assertThat(again.pairingPsk).isNotNull().isEqualTo(first.pairingPsk!!)
        assertThat(box.sealed).hasSize(2)
    }

    @Test
    fun `an identity that cannot be opened is never replaced`(): Unit = runBlocking {
        val box = FakeBox()
        val store = storeWith(box)
        val key = store.load().identity!!.publicKey
        box.opening = false
        assertThat(store.load().identity).isNull()
        // Writing something else keeps it.
        store.setVolume(40, muted = true)
        box.opening = true
        val back = store.load()
        assertThat(back.identity!!.publicKey).isEqualTo(key)
        assertThat(back.volume).isEqualTo(40)
        assertThat(back.muted).isTrue()
    }

    @Test
    fun `pairing records are kept newest first and at most eight`(): Unit = runBlocking {
        val store = storeWith(FakeBox())
        for (i in 1..10) store.remember(PairingRecord("server$i", ByteArray(32) { i.toByte() }))
        store.used("server5")
        val records = store.load().records
        assertThat(records.map { it.serverId }).containsExactly("server5", "server10", "server9", "server8", "server7", "server6", "server4", "server3")
        assertThat(records.first().psk).isEqualTo(ByteArray(32) { 5 })
        store.forget("server5")
        assertThat(store.load().records.map { it.serverId }).doesNotContain("server5")
    }

    @Test
    fun `what had to be kept unsealed is sealed once the keystore can`(): Unit = runBlocking {
        val box = FakeBox()
        box.sealing = false
        val store = storeWith(box)
        val key = store.load().identity!!.publicKey
        store.remember(PairingRecord("server1", ByteArray(32) { 1 }))
        assertThat(box.sealed).isEmpty()
        box.sealing = true
        val back = store.load()
        assertThat(back.identity!!.publicKey).isEqualTo(key)
        assertThat(back.records.single().serverId).isEqualTo("server1")
        // The identity, the pairing PSK and the records, each once.
        assertThat(box.sealed).hasSize(3)
        store.load()
        assertThat(box.sealed).hasSize(3)
    }

    @Test
    fun `records that cannot be opened are kept, unless a new pairing takes their place`(): Unit = runBlocking {
        val box = FakeBox()
        val store = storeWith(box)
        store.remember(PairingRecord("server1", ByteArray(32) { 1 }))
        box.opening = false
        store.used("server1")
        store.forget("server1")
        box.opening = true
        assertThat(store.load().records.map { it.serverId }).containsExactlyInAnyOrder("server1")
        box.opening = false
        store.remember(PairingRecord("server2", ByteArray(32) { 2 }))
        box.opening = true
        assertThat(store.load().records.map { it.serverId }).containsExactlyInAnyOrder("server2")
    }

    @Test
    fun `the output delay stays within what the spec allows`(): Unit = runBlocking {
        val store = storeWith(FakeBox())
        store.setOutputDelay(9_000)
        assertThat(store.load().outputDelayMs).isEqualTo(5_000)
        store.setOutputDelay(-5)
        assertThat(store.load().outputDelayMs).isEqualTo(0)
    }
}
