package io.github.hazymorning.klangbild.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.ma.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.text.TestTexts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsMenuTest {
    private val de = TestTexts.german
    private val configured = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.wz")

    @Test
    fun `switches flip with any key`() {
        val s = Settings()
        assertThat(SettingsMenu.step(MenuEntry.CLOCK, s, +1)!!.showClock).isFalse()
        assertThat(SettingsMenu.step(MenuEntry.CLOCK, s, -1)!!.showClock).isFalse()
        assertThat(SettingsMenu.step(MenuEntry.ALBUM, s, +1, wrap = true)!!.showAlbum).isFalse()
        assertThat(SettingsMenu.step(MenuEntry.UP_DOWN, s, +1)!!.volumeKeys).isTrue()
        assertThat(SettingsMenu.step(MenuEntry.SLEEP_ACTION, s, +1)!!.sleepAction).isEqualTo(SleepAction.CLOSE)
    }

    @Test
    fun `scales stop at their ends with left and right and wrap around with OK`() {
        val normal = Settings()
        assertThat(SettingsMenu.step(MenuEntry.LIGHT, normal, +1)!!.light).isEqualTo(LightLevel.STRONG)
        assertThat(SettingsMenu.step(MenuEntry.LIGHT, normal, -1)!!.light).isEqualTo(LightLevel.SUBTLE)
        val strong = normal.copy(light = LightLevel.STRONG)
        assertThat(SettingsMenu.step(MenuEntry.LIGHT, strong, +1)!!.light).isEqualTo(LightLevel.STRONG)
        assertThat(SettingsMenu.step(MenuEntry.LIGHT, strong, +1, wrap = true)!!.light).isEqualTo(LightLevel.OFF)
        assertThat(SettingsMenu.step(MenuEntry.VOLUME, normal, -1)!!.volumeDisplay).isEqualTo(VolumeDisplay.OFF)
        assertThat(SettingsMenu.step(MenuEntry.VOLUME, normal, +1)!!.volumeDisplay).isEqualTo(VolumeDisplay.ALWAYS)
    }

    @Test
    fun `sleep time steps through the offered values, also from odd ones`() {
        assertThat(SettingsMenu.nextSleep(10, +1)).isEqualTo(15)
        assertThat(SettingsMenu.nextSleep(10, -1)).isEqualTo(5)
        assertThat(SettingsMenu.nextSleep(7, +1)).isEqualTo(10)
        assertThat(SettingsMenu.nextSleep(7, -1)).isEqualTo(5)
        assertThat(SettingsMenu.nextSleep(120, +1)).isEqualTo(120)
        assertThat(SettingsMenu.nextSleep(120, +1, wrap = true)).isEqualTo(5)
        assertThat(SettingsMenu.nextSleep(5, -1)).isEqualTo(5)
        assertThat(SettingsMenu.duration(10, de)).isEqualTo("Zehn Minuten")
        assertThat(SettingsMenu.duration(45, de)).isEqualTo("45 Minuten")
        assertThat(SettingsMenu.duration(60, de)).isEqualTo("Eine Stunde")
        assertThat(SettingsMenu.duration(120, de)).isEqualTo("Zwei Stunden")
        assertThat(SettingsMenu.duration(90, de)).isEqualTo("90 Minuten")
        val en = TestTexts.english()
        assertThat(SettingsMenu.duration(10, en)).isEqualTo("Ten minutes")
        assertThat(SettingsMenu.duration(60, en)).isEqualTo("One hour")
        assertThat(SettingsMenu.duration(120, en)).isEqualTo("Two hours")
        assertThat(SettingsMenu.delay(1, en)).isEqualTo("One second")
        assertThat(SettingsMenu.delay(15, en)).isEqualTo("15 seconds")
    }

    @Test
    fun `lights that are off are set up first, then turned on or left off`() {
        assertThat(SettingsMenu.value(MenuEntry.LIGHTS_ONLY_ON, configured, MenuContext(), de)).isEqualTo("Einrichten")
        assertThat(SettingsMenu.step(MenuEntry.LIGHTS_ONLY_ON, configured, +1)).isNull()
        val lights = configured.copy(roomLights = RoomLights(left = "light.links"))
        assertThat(SettingsMenu.value(MenuEntry.LIGHTS_ONLY_ON, lights, MenuContext(), de)).isEqualTo("Einschalten")
        val leaveOff = SettingsMenu.step(MenuEntry.LIGHTS_ONLY_ON, lights, +1)!!
        assertThat(leaveOff.lightsOnlyOn).isTrue()
        assertThat(SettingsMenu.value(MenuEntry.LIGHTS_ONLY_ON, leaveOff, MenuContext(), de)).isEqualTo("Auslassen")
        assertThat(SettingsMenu.step(MenuEntry.LIGHTS_ONLY_ON, leaveOff, -1)!!.lightsOnlyOn).isFalse()
    }

    @Test
    fun `every line has a value and an explanation, actions have no step`() {
        val withMa = configured.copy(maUrl = "http://192.168.1.5:8095", maToken = "t")
        val withLights = configured.copy(roomLights = RoomLights(left = "light.links", right = "light.rechts"))
        val withValues = configured.copy(ownValues = listOf(OwnValue("Außen", "sensor.outside")))
        val contexts = listOf(
            MenuContext(),
            MenuContext(LinkState.CONNECTED, ma = MaLink.CONNECTED),
            MenuContext(LinkState.AUTH_FAILED, ma = MaLink.AUTH_FAILED),
            MenuContext(sleep = SleepChoice.MIN_30, sleepAt = 1_791_483_667_000L),
            MenuContext(sleep = SleepChoice.ITEM_END, kind = MediaKind.PODCAST),
        )
        for (s in listOf(Settings(), configured, configured.copy(light = LightLevel.OFF, volumeKeys = true), withMa, withLights, withValues)) {
            // The lines that are no stored setting: the connections, the sleep timer, the language
            // (the system keeps it), and the lights and the values until they are set up.
            val actions = setOfNotNull(
                MenuEntry.CONNECTION, MenuEntry.MUSIC_ASSISTANT, MenuEntry.SLEEP_TIMER, MenuEntry.LANGUAGE,
                MenuEntry.LIGHTS.takeIf { !s.roomLights.isSet },
                MenuEntry.LIGHTS_ONLY_ON.takeIf { !s.roomLights.isSet },
                MenuEntry.VALUES.takeIf { s.ownValues.isEmpty() },
            )
            for (ctx in contexts) {
                for (row in SettingsMenu.rows(s, ctx, de)) {
                    assertThat(row.value).isNotEmpty()
                    assertThat(row.description).endsWith(".")
                    assertThat(SettingsMenu.step(row.entry, s, +1) == null).isEqualTo(row.entry in actions)
                }
            }
        }
        assertThat(SettingsMenu.sleepValue(SleepChoice.ITEM_END, MediaKind.PODCAST, de)).isEqualTo("Ende der Folge")
        assertThat(SettingsMenu.sleepValue(SleepChoice.MIN_60, null, de)).isEqualTo("Eine Stunde")
        assertThat(SettingsMenu.value(MenuEntry.CONNECTION, Settings(), MenuContext(), de)).isEqualTo("Einrichten")
        assertThat(SettingsMenu.value(MenuEntry.CONNECTION, configured, MenuContext(LinkState.CONNECTED), de)).isEqualTo("Verbunden")
        assertThat(SettingsMenu.value(MenuEntry.CONNECTION, configured, MenuContext(LinkState.AUTH_FAILED), de)).isEqualTo("Zugang abgelehnt")
        assertThat(SettingsMenu.value(MenuEntry.MUSIC_ASSISTANT, configured, MenuContext(ma = MaLink.CONNECTED), de)).isEqualTo("Nicht eingerichtet")
        assertThat(SettingsMenu.value(MenuEntry.MUSIC_ASSISTANT, withMa, MenuContext(ma = MaLink.UNREACHABLE), de)).isEqualTo("Nicht erreichbar")
        // The values: set up on the phone first, then shown or hidden.
        assertThat(SettingsMenu.value(MenuEntry.VALUES, configured, MenuContext(), de)).isEqualTo("Einrichten")
        assertThat(SettingsMenu.value(MenuEntry.VALUES, withValues, MenuContext(), de)).isEqualTo("An")
        assertThat(SettingsMenu.step(MenuEntry.VALUES, withValues, +1)!!.showValues).isFalse()
    }

    @Test
    fun `the window view's delays step through the offered values, also from odd ones`() {
        val s = Settings()
        assertThat(SettingsMenu.value(MenuEntry.VISTA_PAUSE, s, MenuContext(), de)).isEqualTo("Drei Minuten")
        assertThat(SettingsMenu.value(MenuEntry.VISTA_PLAYING, s, MenuContext(), de)).isEqualTo("Aus")
        assertThat(SettingsMenu.step(MenuEntry.VISTA_PAUSE, s, +1)!!.vistaPauseSeconds).isEqualTo(300)
        assertThat(SettingsMenu.step(MenuEntry.VISTA_PAUSE, s.copy(vistaPauseSeconds = 0), -1)!!.vistaPauseSeconds).isEqualTo(0)
        assertThat(SettingsMenu.step(MenuEntry.VISTA_PAUSE, s.copy(vistaPauseSeconds = 600), +1, wrap = true)!!.vistaPauseSeconds).isEqualTo(0)
        // Set by a link to 100 s: the nearest offered value is the start.
        assertThat(SettingsMenu.step(MenuEntry.VISTA_PAUSE, s.copy(vistaPauseSeconds = 100), +1)!!.vistaPauseSeconds).isEqualTo(180)
        assertThat(SettingsMenu.step(MenuEntry.VISTA_PLAYING, s, +1)!!.vistaPlayingSeconds).isEqualTo(60)
        assertThat(SettingsMenu.delay(15, de)).isEqualTo("15 Sekunden")
        assertThat(SettingsMenu.delay(60, de)).isEqualTo("Eine Minute")
        assertThat(SettingsMenu.delay(5, de)).isEqualTo("Fünf Sekunden")
        assertThat(SettingsMenu.step(MenuEntry.MINI_PLAYER, s, +1)!!.miniPlayer).isEqualTo(MiniPlayer.RIGHT)
        assertThat(SettingsMenu.step(MenuEntry.MINI_PLAYER, s, -1)!!.miniPlayer).isEqualTo(MiniPlayer.OFF)
        assertThat(SettingsMenu.value(MenuEntry.MINI_PLAYER, s, MenuContext(), de)).isEqualTo("Unten links")
    }

    @Test
    fun `sections stay together in menu order`() {
        val sections = SettingsMenu.entries.map { it.section }
        assertThat(sections).isInOrder(compareBy<MenuSection> { it.ordinal })
    }
}
