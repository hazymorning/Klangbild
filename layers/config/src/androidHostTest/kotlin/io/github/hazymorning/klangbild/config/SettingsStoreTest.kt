package io.github.hazymorning.klangbild.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.domain.Condition
import io.github.hazymorning.klangbild.domain.OwnValue
import io.github.hazymorning.klangbild.domain.QuickAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import kotlin.test.Test

/** Tokens are kept sealed: written sealed, read back opened, and old plain ones sealed on the next start. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsStoreTest {
    /** Seals by reversing behind a mark, and notes what it was given to open: what the store holds. */
    private class FakeBox(private val sealing: Boolean = true, private val opening: Boolean = true) : SecretBox {
        val stored = mutableListOf<String>()
        override fun seal(plain: String) = if (sealing) "sealed:" + plain.reversed() else plain
        override fun open(stored: String): String? {
            this.stored += stored
            if (!opening) return null
            return if (isPlain(stored)) stored else stored.removePrefix("sealed:").reversed()
        }
        override fun isPlain(stored: String) = !stored.startsWith("sealed:")
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `tokens are stored sealed, read back as they were, and only kept for their address`(): Unit = runBlocking {
        val box = FakeBox()
        val store = SettingsStore(settingsValues(context), box)
        val signedIn = Settings().withHa("http://192.168.1.5:8123", HaCredentials.SignedIn("refresh-secret", "http://192.168.1.23:8765/"))
            .withMa("http://192.168.1.5:8095", "ma-secret")
            .copy(entityId = "media_player.living_room")
        store.write(signedIn)
        val back = store.settings.first()
        assertThat(back.haCredentials).isEqualTo(HaCredentials.SignedIn("refresh-secret", "http://192.168.1.23:8765/"))
        assertThat(back.maToken).isEqualTo("ma-secret")
        // What the store holds is sealed: no secret in it as it is.
        assertThat(box.stored).isNotEmpty()
        assertThat(box.stored.none { it.contains("secret") }).isTrue()
        assertThat(box.stored.all { it.startsWith("sealed:") }).isTrue()

        // Another address without its credentials drops the old ones.
        store.write(back.withHa("http://10.0.0.9:8123", null))
        assertThat(store.settings.first().haCredentials).isNull()
    }

    @Test
    fun `every setting is read back as it was stored`(): Unit = runBlocking {
        val changed = Settings(
            haUrl = "http://192.168.1.5:8123",
            haToken = "long-lived",
            haRefreshToken = "refresh",
            haClientId = "http://192.168.1.23:8765/",
            entityId = "media_player.kitchen",
            otherSpeaker = "media_player.living_room",
            sunEntity = "sun.home",
            sleepAfterMinutes = 45,
            sleepAction = SleepAction.CLOSE,
            showClock = false,
            remoteControl = false,
            showProgressBar = false,
            showNext = false,
            showAlbum = false,
            showGroup = false,
            volumeDisplay = VolumeDisplay.ALWAYS,
            showValues = false,
            intensity = Intensity.STRONG,
            volumeKeys = true,
            appearance = AppearanceMode.DARK,
            queue = QueueDisplay.LIST,
            lyrics = true,
            maUrl = "http://192.168.1.5:8095",
            maToken = "ma-token",
            tvSpeaker = true,
            background = BackgroundMode.COVER,
            motion = MotionLevel.LIVELY,
            roomLights = RoomLights("light.left", "light.top", "light.right"),
            roomLightBrightness = RoomLightBrightness.P90,
            lightsOnlyOn = true,
            spokenSkip = false,
            quickActions = listOf(QuickAction("Ungroup", "script.ungroup", condition = Condition("media_player.kitchen", "group_members", Condition.Test.ABOVE, "1"))),
            ownValues = listOf(OwnValue("Outside", "sensor.outside_temperature")),
            quickSeen = true,
        )
        // A setting the store forgets would come back as its default, so every one differs from it here.
        val defaults = Settings()
        for (field in Settings::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }) {
            field.isAccessible = true
            assertThat(field.get(changed), name = "${field.name} is set apart from its default").isNotEqualTo(field.get(defaults))
        }
        val store = SettingsStore(settingsValues(context), FakeBox())
        store.write(changed)
        assertThat(store.settings.first()).isEqualTo(changed)
    }

    @Test
    fun `the store keeps the buttons`(): Unit = runBlocking {
        val store = SettingsStore(settingsValues(context), KeystoreSecretBox())
        val grouped = QuickAction("Ungroup", "script.ungroup_speakers").copy(
            variables = Json.parseToJsonElement("""{"mode":"all"}""").jsonObject,
            condition = Condition("media_player.wz", "group_members", Condition.Test.ABOVE, "1"),
        )
        val party = QuickAction("Party mode", "input_boolean.party")
        val values = listOf(OwnValue("Outside", "sensor.outside"), OwnValue("", "weather.home", "temperature"))
        store.write(Settings(quickActions = listOf(grouped, party), ownValues = values, showValues = false))
        val back = store.settings.first()
        assertThat(back.quickActions).containsExactly(grouped, party)
        assertThat(back.ownValues).containsExactly(*values.toTypedArray())
        assertThat(back.showValues).isFalse()
        store.write(back.copy(quickActions = emptyList(), ownValues = emptyList()))
        assertThat(store.settings.first().quickActions).isEmpty()
        assertThat(store.settings.first().ownValues).isEmpty()
    }

    @Test
    fun `the view an earlier version stored becomes how the queue shows, and its key goes`() {
        val store = SettingsStore(settingsValues(context), FakeBox())
        val view = "view"
        val queue = "queue"
        // Cover Flow stays Cover Flow, and the single cover, which had no list, is the queue off.
        fun after(stored: String): Map<String, Any> = mutableMapOf<String, Any>(view to stored).also { store.migrate(it) }
        assertThat(after("FLOW")[queue]).isEqualTo("FLOW")
        assertThat(after("SINGLE")[queue]).isEqualTo("OFF")
        assertThat(after("SINGLE").contains(view)).isFalse()
        assertThat(store.read(after("FLOW")).queue).isEqualTo(QueueDisplay.FLOW)
        // Read before the step has run, it means the same.
        assertThat(store.read(mapOf(view to "FLOW")).queue).isEqualTo(QueueDisplay.FLOW)
        assertThat(store.read(mapOf(view to "SINGLE")).queue).isEqualTo(QueueDisplay.OFF)
        // A value it does not know reads as the default, and the queue's own key wins.
        assertThat(store.read(mapOf(view to "ELSEWHERE")).queue).isEqualTo(Settings().queue)
        assertThat(store.read(mapOf(view to "FLOW", queue to "LIST")).queue).isEqualTo(QueueDisplay.LIST)
    }

    @Test
    fun `moving the view touches nothing else, not even a token that cannot be opened now`() {
        // The keystore cannot open the sealed token at this moment: it must stay as it is.
        val store = SettingsStore(settingsValues(context), FakeBox(opening = false))
        val p = mutableMapOf<String, Any>("view" to "FLOW", "ha_token" to "sealed:terces", "ha_url" to "http://192.168.1.5:8123")
        store.migrate(p)
        assertThat(p).isEqualTo(
            mapOf(
                "queue" to "FLOW",
                "ha_token" to "sealed:terces",
                "ha_url" to "http://192.168.1.5:8123",
            ),
        )
    }

    @Test
    fun `settings that no longer exist go, and only they`() {
        val store = SettingsStore(settingsValues(context), FakeBox(opening = false))
        val p = mutableMapOf<String, Any>(
            "vista_pause_s" to 180,
            "vista_playing_s" to 0,
            "mini_player" to "LEFT",
            "weather_entity" to "weather.home",
            "ha_token" to "sealed:terces",
            "queue" to "LIST",
        )
        store.migrate(p)
        assertThat(p).isEqualTo(mapOf("ha_token" to "sealed:terces", "queue" to "LIST"))
    }

    @Test
    fun `tokens stored by an earlier version are sealed on the next start`(): Unit = runBlocking {
        // An earlier version wrote the token as it is.
        SettingsStore(settingsValues(context), FakeBox(sealing = false))
            .write(Settings().withHa("http://192.168.1.5:8123", HaCredentials.LongLived("long-lived-secret")))

        val box = FakeBox()
        val store = SettingsStore(settingsValues(context), box)
        assertThat(store.settings.first().haCredentials).isEqualTo(HaCredentials.LongLived("long-lived-secret"))
        assertThat(box.stored).contains("long-lived-secret")
        store.migrate()
        box.stored.clear()
        assertThat(store.settings.first().haCredentials).isEqualTo(HaCredentials.LongLived("long-lived-secret"))
        assertThat(box.stored).containsExactlyInAnyOrder("sealed:" + "long-lived-secret".reversed())
    }

    @Test
    fun `without a keystore secrets pass through as they are`() {
        // The JVM has no Android keystore: the box keeps the app working, unsealed.
        val box = KeystoreSecretBox()
        assertThat(box.open(box.seal("token"))).isEqualTo("token")
    }
}
