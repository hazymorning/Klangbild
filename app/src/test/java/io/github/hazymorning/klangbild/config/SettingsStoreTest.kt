package io.github.hazymorning.klangbild.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tokens are kept sealed: written sealed, read back opened, and old plain ones sealed on the next start. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsStoreTest {
    /** Seals by reversing behind a mark, and notes what it was given to open: what the store holds. */
    private class FakeBox(private val sealing: Boolean = true) : SecretBox {
        val stored = mutableListOf<String>()
        override fun seal(plain: String) = if (sealing) "sealed:" + plain.reversed() else plain
        override fun open(stored: String): String {
            this.stored += stored
            return if (isPlain(stored)) stored else stored.removePrefix("sealed:").reversed()
        }
        override fun isPlain(stored: String) = !stored.startsWith("sealed:")
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `tokens are stored sealed, read back as they were, and only kept for their address`(): Unit = runBlocking {
        val box = FakeBox()
        val store = SettingsStore(context, box)
        val signedIn = Settings().withHa("http://192.168.1.5:8123", HaCredentials.SignedIn("refresh-secret", "http://192.168.1.23:8765/"))
            .withMa("http://192.168.1.5:8095", "ma-secret")
            .copy(entityId = "media_player.living_room")
        store.update({ signedIn }, Settings())
        val back = store.settings.first()
        assertThat(back.haCredentials).isEqualTo(HaCredentials.SignedIn("refresh-secret", "http://192.168.1.23:8765/"))
        assertThat(back.maToken).isEqualTo("ma-secret")
        // What the store holds is sealed: no secret in it as it is.
        assertThat(box.stored).isNotEmpty()
        assertThat(box.stored.none { it.contains("secret") }).isTrue()
        assertThat(box.stored.all { it.startsWith("sealed:") }).isTrue()

        // Another address without its credentials drops the old ones.
        store.update({ it.withHa("http://10.0.0.9:8123", null) }, back)
        assertThat(store.settings.first().haCredentials).isNull()
    }

    @Test
    fun `the song's words are remembered like the view`(): Unit = runBlocking {
        val store = SettingsStore(context, FakeBox())
        assertThat(store.settings.first().lyrics).isFalse()
        store.update({ it.copy(lyrics = true) }, Settings())
        assertThat(store.settings.first().lyrics).isTrue()
    }

    @Test
    fun `whether lights that are off are left off is remembered`(): Unit = runBlocking {
        val store = SettingsStore(context, FakeBox())
        assertThat(store.settings.first().lightsOnlyOn).isFalse()
        store.update({ it.copy(lightsOnlyOn = true) }, Settings())
        assertThat(store.settings.first().lightsOnlyOn).isTrue()
    }

    @Test
    fun `tokens stored by an earlier version are sealed on the next start`(): Unit = runBlocking {
        // An earlier version wrote the token as it is.
        SettingsStore(context, FakeBox(sealing = false))
            .update({ it.withHa("http://192.168.1.5:8123", HaCredentials.LongLived("long-lived-secret")) }, Settings())

        val box = FakeBox()
        val store = SettingsStore(context, box)
        assertThat(store.settings.first().haCredentials).isEqualTo(HaCredentials.LongLived("long-lived-secret"))
        assertThat(box.stored).contains("long-lived-secret")
        store.sealStoredSecrets()
        box.stored.clear()
        assertThat(store.settings.first().haCredentials).isEqualTo(HaCredentials.LongLived("long-lived-secret"))
        assertThat(box.stored).containsExactly("sealed:" + "long-lived-secret".reversed())
    }

    @Test
    fun `without a keystore secrets pass through as they are`() {
        // The JVM has no Android keystore: the box keeps the app working, unsealed.
        val box = KeystoreSecretBox()
        assertThat(box.open(box.seal("token"))).isEqualTo("token")
    }
}
