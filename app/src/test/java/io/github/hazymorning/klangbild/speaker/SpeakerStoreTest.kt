package io.github.hazymorning.klangbild.speaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.sendspin.PairingRecord
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeakerStoreTest {
    /** Seals by reversing behind a mark; [opening] off plays a keystore that refuses. */
    private class FakeBox : SecretBox {
        var opening = true
        val sealed = mutableListOf<String>()
        override fun seal(plain: String) = ("sealed:" + plain.reversed()).also { sealed += it }
        override fun open(stored: String): String? = if (!opening) null else stored.removePrefix("sealed:").reversed()
        override fun isPlain(stored: String) = !stored.startsWith("sealed:")
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the identity is made once and kept, sealed`(): Unit = runBlocking {
        val box = FakeBox()
        val store = SpeakerStore(context, box)
        val first = store.load()
        val again = store.load()
        assertThat(first.identity).isNotNull()
        assertThat(again.identity!!.publicKey).isEqualTo(first.identity!!.publicKey)
        assertThat(again.pairingPsk).isEqualTo(first.pairingPsk)
        assertThat(box.sealed).hasSize(2)
    }

    @Test
    fun `an identity that cannot be opened is never replaced`(): Unit = runBlocking {
        val box = FakeBox()
        val store = SpeakerStore(context, box)
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
        val store = SpeakerStore(context, FakeBox())
        for (i in 1..10) store.remember(PairingRecord("server$i", ByteArray(32) { i.toByte() }))
        store.used("server5")
        val records = store.load().records
        assertThat(records.map { it.serverId }).containsExactly("server5", "server10", "server9", "server8", "server7", "server6", "server4", "server3").inOrder()
        assertThat(records.first().psk).isEqualTo(ByteArray(32) { 5 })
        store.forget("server5")
        assertThat(store.load().records.map { it.serverId }).doesNotContain("server5")
    }

    @Test
    fun `the output delay stays within what the spec allows`(): Unit = runBlocking {
        val store = SpeakerStore(context, FakeBox())
        store.setOutputDelay(9_000)
        assertThat(store.load().outputDelayMs).isEqualTo(5_000)
        store.setOutputDelay(-5)
        assertThat(store.load().outputDelayMs).isEqualTo(0)
    }
}
