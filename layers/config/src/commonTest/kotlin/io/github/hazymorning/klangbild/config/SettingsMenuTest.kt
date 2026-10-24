package io.github.hazymorning.klangbild.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.OwnValue
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.UpdateInfo
import io.github.hazymorning.klangbild.domain.UpdateState
import io.github.hazymorning.klangbild.text.TestTexts
import kotlin.test.Test

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
        assertThat(SettingsMenu.step(MenuEntry.INTENSITY, normal, +1)!!.intensity).isEqualTo(Intensity.STRONG)
        assertThat(SettingsMenu.step(MenuEntry.INTENSITY, normal, -1)!!.intensity).isEqualTo(Intensity.SUBTLE)
        val strong = normal.copy(intensity = Intensity.STRONG)
        assertThat(SettingsMenu.step(MenuEntry.INTENSITY, strong, +1)!!.intensity).isEqualTo(Intensity.STRONG)
        assertThat(SettingsMenu.step(MenuEntry.INTENSITY, strong, +1, wrap = true)!!.intensity).isEqualTo(Intensity.OFF)
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
        assertThat(SettingsMenu.value(MenuEntry.OFF_LIGHTS, configured, MenuContext(), de)).isEqualTo("Einrichten")
        assertThat(SettingsMenu.step(MenuEntry.OFF_LIGHTS, configured, +1)).isNull()
        val lights = configured.copy(roomLights = RoomLights(left = "light.links"))
        assertThat(SettingsMenu.value(MenuEntry.OFF_LIGHTS, lights, MenuContext(), de)).isEqualTo("Einschalten")
        val leaveOff = SettingsMenu.step(MenuEntry.OFF_LIGHTS, lights, +1)!!
        assertThat(leaveOff.lightsOnlyOn).isTrue()
        assertThat(SettingsMenu.value(MenuEntry.OFF_LIGHTS, leaveOff, MenuContext(), de)).isEqualTo("Auslassen")
        assertThat(SettingsMenu.step(MenuEntry.OFF_LIGHTS, leaveOff, -1)!!.lightsOnlyOn).isFalse()
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
            MenuContext(LinkState.CONNECTED, ma = MaLink.CONNECTED, speaker = SpeakerLink.TOO_OLD),
            MenuContext(update = UpdateInfo("2026.10.5", "v2026.11.0")),
            MenuContext(update = UpdateInfo("2026.10.5", "v2026.11.0", hidden = "v2026.11.0", state = UpdateState.DOWNLOADING)),
            MenuContext(update = UpdateInfo("2026.10.5", "v2026.10.5", state = UpdateState.FAILED)),
            MenuContext(update = UpdateInfo("2026.10.24 (abc1234)")),
        )
        for (s in listOf(Settings(), configured, configured.copy(intensity = Intensity.OFF, volumeKeys = true), withMa, withMa.copy(tvSpeaker = true), withLights, withValues)) {
            // The lines that are no stored setting: the connections, the sleep timer, the language
            // (the system keeps it), the update, and the lights and the values until they are set up.
            val needs = setOfNotNull(
                MenuEntry.BRIGHTNESS.takeIf { !s.roomLights.isSet },
                MenuEntry.OFF_LIGHTS.takeIf { !s.roomLights.isSet },
                MenuEntry.VALUES.takeIf { s.ownValues.isEmpty() },
                MenuEntry.LYRICS.takeIf { !s.hasMusicAssistant },
                MenuEntry.SPEAKER.takeIf { !s.hasMusicAssistant },
                MenuEntry.PLAY_HERE.takeIf { !s.hasMusicAssistant },
            )
            val actions = setOf(MenuEntry.CONNECTION, MenuEntry.MUSIC_ASSISTANT, MenuEntry.SLEEP_TIMER, MenuEntry.LANGUAGE, MenuEntry.UPDATE) + needs
            for (ctx in contexts) {
                for (row in SettingsMenu.sections.flatMap { SettingsMenu.rows(s, ctx, de, it) }) {
                    val entry = row.entry!!
                    assertThat(row.value).isNotEmpty()
                    assertThat(row.description).endsWith(".")
                    assertThat(SettingsMenu.step(entry, s, +1) == null).isEqualTo(entry in actions)
                    // What needs something set up first is dimmed, and says so.
                    assertThat(row.dimmed).isEqualTo(entry in needs)
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
    fun `the landscape's delays step through the offered values, also from odd ones`() {
        val s = Settings()
        assertThat(SettingsMenu.value(MenuEntry.LANDSCAPE_PAUSE, s, MenuContext(), de)).isEqualTo("Drei Minuten")
        assertThat(SettingsMenu.value(MenuEntry.LANDSCAPE_PLAYING, s, MenuContext(), de)).isEqualTo("Aus")
        assertThat(SettingsMenu.step(MenuEntry.LANDSCAPE_PAUSE, s, +1)!!.landscapePauseSeconds).isEqualTo(300)
        assertThat(SettingsMenu.step(MenuEntry.LANDSCAPE_PAUSE, s.copy(landscapePauseSeconds = 0), -1)!!.landscapePauseSeconds).isEqualTo(0)
        assertThat(SettingsMenu.step(MenuEntry.LANDSCAPE_PAUSE, s.copy(landscapePauseSeconds = 600), +1, wrap = true)!!.landscapePauseSeconds).isEqualTo(0)
        // Set by a link to 100 s: the nearest offered value is the start.
        assertThat(SettingsMenu.step(MenuEntry.LANDSCAPE_PAUSE, s.copy(landscapePauseSeconds = 100), +1)!!.landscapePauseSeconds).isEqualTo(180)
        assertThat(SettingsMenu.step(MenuEntry.LANDSCAPE_PLAYING, s, +1)!!.landscapePlayingSeconds).isEqualTo(60)
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
        assertThat(sections).isEqualTo(sections.sortedBy { it.ordinal })
    }

    @Test
    fun `the first level shows the groups, each saying what is in it, and every setting is in one`() {
        val groups = SettingsMenu.groups(MenuContext(), de)
        assertThat(groups.map { it.label }).containsExactly("Bild", "Informationen", "Licht", "Landschaft", "Fernbedienung", "Timer", "System")
        for (g in groups) {
            assertThat(g.entry).isNull()
            assertThat(g.description).endsWith(".")
            assertThat(SettingsMenu.rows(configured, MenuContext(), de, g.section!!)).isNotEmpty()
        }
        assertThat(SettingsMenu.sections.flatMap { SettingsMenu.entriesOf(it) }).containsExactly(*(SettingsMenu.entries).toTypedArray())
        assertThat(SettingsMenu.entriesOf(MenuSection.PICTURE)).containsExactly(
            MenuEntry.QUEUE, MenuEntry.APPEARANCE, MenuEntry.BACKGROUND, MenuEntry.INTENSITY, MenuEntry.MOTION,
        )
        assertThat(SettingsMenu.entriesOf(MenuSection.SYSTEM)).containsExactly(
            MenuEntry.CONNECTION, MenuEntry.MUSIC_ASSISTANT, MenuEntry.SPEAKER, MenuEntry.PLAY_HERE, MenuEntry.LANGUAGE, MenuEntry.UPDATE,
        )
    }

    @Test
    fun `the update line says what is known of a new version, and the system group tells of one until it is hidden`() {
        fun ctx(latest: String?, hidden: String? = null, state: UpdateState = UpdateState.IDLE, name: String = "2026.10.5") =
            MenuContext(update = UpdateInfo(name, latest, hidden, state))
        fun value(c: MenuContext) = SettingsMenu.value(MenuEntry.UPDATE, configured, c, de)
        assertThat(value(ctx(null))).isEqualTo("Nicht geprüft")
        assertThat(value(ctx(null, state = UpdateState.CHECKING))).isEqualTo("Wird geprüft")
        assertThat(value(ctx("v2026.10.5"))).isEqualTo("Auf dem neuesten Stand")
        assertThat(value(ctx("v2026.11.0"))).isEqualTo("Version\u00A02026.11.0")
        assertThat(value(ctx("v2026.11.0", state = UpdateState.DOWNLOADING))).isEqualTo("Wird geladen")
        assertThat(value(ctx("v2026.11.0", state = UpdateState.FAILED))).isEqualTo("Hat nicht geklappt")
        // A build of one's own names itself and looks for nothing.
        assertThat(value(ctx("v2026.11.0", name = "2026.10.24 (abc1234)"))).isEqualTo("2026.10.24 (abc1234)")
        fun system(c: MenuContext) = SettingsMenu.groups(c, de).single { it.section == MenuSection.SYSTEM }.value
        assertThat(system(ctx("v2026.11.0"))).isEqualTo("Neue Version")
        assertThat(system(ctx("v2026.11.0", hidden = "v2026.11.0"))).isEqualTo("")
        assertThat(system(ctx("v2026.10.5"))).isEqualTo("")
        // The explanation says what the keys do now.
        assertThat(SettingsMenu.description(MenuEntry.UPDATE, configured, ctx("v2026.11.0"), de)).contains("blendet den\u00A0Hinweis im\u00A0Menü aus")
        assertThat(SettingsMenu.description(MenuEntry.UPDATE, configured, ctx("v2026.11.0", hidden = "v2026.11.0"), de)).contains("zeigt den\u00A0Hinweis im\u00A0Menü wieder")
    }

    @Test
    fun `the song's words are a setting too, and without Music Assistant they say what they need`() {
        val lyrics = SettingsMenu.rows(configured, MenuContext(), de, MenuSection.INFO).last()
        assertThat(lyrics.label).isEqualTo("Songtext")
        assertThat(lyrics.value).isEqualTo("Nur mit Music\u00A0Assistant")
        assertThat(lyrics.dimmed).isTrue()
        assertThat(SettingsMenu.step(MenuEntry.LYRICS, configured, +1)).isNull()
        val withMa = configured.copy(maUrl = "http://192.168.1.5:8095", maToken = "t")
        val on = SettingsMenu.step(MenuEntry.LYRICS, withMa, +1)!!
        assertThat(on.lyrics).isTrue()
        assertThat(SettingsMenu.value(MenuEntry.LYRICS, on, MenuContext(), de)).isEqualTo("An")
        assertThat(SettingsMenu.rows(on, MenuContext(), de, MenuSection.INFO).last().dimmed).isFalse()
        assertThat(SettingsMenu.value(MenuEntry.LYRICS, withMa, MenuContext(), TestTexts.english())).isEqualTo("Off")
    }

    @Test
    fun `playing here is a setting that needs Music Assistant, and says what keeps it from playing`() {
        val row = SettingsMenu.rows(configured, MenuContext(), de, MenuSection.SYSTEM).first { it.entry == MenuEntry.PLAY_HERE }
        assertThat(row.label).isEqualTo("Hier abspielen")
        assertThat(row.value).isEqualTo("Nur mit Music\u00A0Assistant")
        assertThat(row.dimmed).isTrue()
        assertThat(SettingsMenu.step(MenuEntry.PLAY_HERE, configured, +1)).isNull()
        val withMa = configured.copy(maUrl = "http://192.168.1.5:8095", maToken = "t")
        assertThat(withMa.tvSpeaker).isFalse()
        val on = SettingsMenu.step(MenuEntry.PLAY_HERE, withMa, +1)!!
        assertThat(on.tvSpeaker).isTrue()
        assertThat(on.speakerOn).isTrue()
        assertThat(SettingsMenu.value(MenuEntry.PLAY_HERE, on, MenuContext(), de)).isEqualTo("An")
        val en = TestTexts.english()
        assertThat(SettingsMenu.label(MenuEntry.PLAY_HERE, en)).isEqualTo("Play here")
        val said = SpeakerLink.entries.associateWith { SettingsMenu.description(MenuEntry.PLAY_HERE, on, MenuContext(speaker = it), en) }
        assertThat(said[SpeakerLink.TOO_OLD]).isNotNull().contains("2.10.7")
        assertThat(said[SpeakerLink.UNREACHABLE]).isNotNull().contains("8927")
        assertThat(said[SpeakerLink.CONNECTED]).isEqualTo(said[SpeakerLink.CONNECTING])
        // What keeps it from playing is said only while it is on.
        assertThat(SettingsMenu.description(MenuEntry.PLAY_HERE, withMa, MenuContext(speaker = SpeakerLink.TOO_OLD), en)).doesNotContain("2.10.7")
        // While this TV is the speaker followed, it stays on here: OK opens the setup instead.
        val followed = on.copy(entityId = EntityId.THIS_TV)
        assertThat(SettingsMenu.step(MenuEntry.PLAY_HERE, followed, +1)).isNull()
        assertThat(SettingsMenu.opensSetup(MenuEntry.PLAY_HERE, followed)).isTrue()
        assertThat(SettingsMenu.opensSetup(MenuEntry.PLAY_HERE, on)).isFalse()
        assertThat(SettingsMenu.description(MenuEntry.PLAY_HERE, followed, MenuContext(), en)).contains("choose another speaker")
        // What keeps it from playing is still said then.
        assertThat(SettingsMenu.description(MenuEntry.PLAY_HERE, followed, MenuContext(speaker = SpeakerLink.TOO_OLD), en)).contains("2.10.7")
        assertThat(SettingsMenu.description(MenuEntry.PLAY_HERE, followed, MenuContext(speaker = SpeakerLink.NO_KEY), en)).contains("keeps trying")
    }

    @Test
    fun `the speaker switches between the one followed and this TV, which then plays here`() {
        val en = TestTexts.english()
        assertThat(SettingsMenu.label(MenuEntry.SPEAKER, en)).isEqualTo("Speaker")
        assertThat(SettingsMenu.value(MenuEntry.SPEAKER, configured, MenuContext(), de)).isEqualTo("Nur mit Music\u00A0Assistant")
        assertThat(SettingsMenu.step(MenuEntry.SPEAKER, configured, +1)).isNull()
        val withMa = configured.copy(maUrl = "http://192.168.1.5:8095", maToken = "t")
        assertThat(SettingsMenu.value(MenuEntry.SPEAKER, withMa, MenuContext(speakerName = "Wohnzimmer"), de)).isEqualTo("Wohnzimmer")
        // Until Home Assistant has told its name, the entity's stands in.
        assertThat(SettingsMenu.value(MenuEntry.SPEAKER, withMa, MenuContext(), de)).isEqualTo("Wz")
        val tv = SettingsMenu.step(MenuEntry.SPEAKER, withMa, +1)!!
        assertThat(tv.entityId).isEqualTo(EntityId.THIS_TV)
        assertThat(tv.speakerOn).isTrue()
        assertThat(tv.otherSpeaker).isEqualTo("media_player.wz")
        assertThat(SettingsMenu.value(MenuEntry.SPEAKER, tv, MenuContext(speakerName = "Wohnzimmer"), de)).isEqualTo("Dieser Fernseher")
        assertThat(SettingsMenu.opensSetup(MenuEntry.SPEAKER, tv)).isFalse()
        // Back again, and this TV still plays as one of the speakers.
        val back = SettingsMenu.step(MenuEntry.SPEAKER, tv, -1)!!
        assertThat(back.entityId).isEqualTo("media_player.wz")
        assertThat(back.tvSpeaker).isTrue()
        // Playing here turned off while this TV is followed goes back too.
        assertThat(SettingsMenu.opensSetup(MenuEntry.PLAY_HERE, tv)).isFalse()
        assertThat(SettingsMenu.description(MenuEntry.PLAY_HERE, tv, MenuContext(), en)).contains("Turned off")
        val off = SettingsMenu.step(MenuEntry.PLAY_HERE, tv, +1)!!
        assertThat(off.entityId).isEqualTo("media_player.wz")
        assertThat(off.tvSpeaker).isFalse()
        // This TV chosen on the phone with no speaker before it: another one is chosen there.
        val only = withMa.copy(entityId = EntityId.THIS_TV, tvSpeaker = true)
        assertThat(SettingsMenu.step(MenuEntry.SPEAKER, only, +1)).isNull()
        assertThat(SettingsMenu.opensSetup(MenuEntry.SPEAKER, only)).isTrue()
        assertThat(SettingsMenu.description(MenuEntry.SPEAKER, only, MenuContext(), en)).contains("QR")
    }
}
