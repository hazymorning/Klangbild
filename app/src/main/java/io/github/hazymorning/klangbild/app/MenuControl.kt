package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsMenu
import io.github.hazymorning.klangbild.domain.TrackInfo

/**
 * The settings menu while it is open: which line the cursor is on, and what a key does to that
 * line. An untouched menu closes by itself after [IDLE_MS]: it must not stand still on an OLED.
 */
internal class MenuControl(private val host: ControllerHost, private val sleep: SleepControl) {
    @Volatile private var cursor: Int? = null
    @Volatile private var touchedAt = 0L

    val isOpen: Boolean get() = cursor != null

    fun open() {
        synchronized(host.lock) {
            cursor = cursor ?: 0
            touchedAt = host.now()
            host.recompute()
        }
    }

    fun close() {
        synchronized(host.lock) {
            cursor = null
            host.recompute()
        }
    }

    /** Up (-1) or down (+1) through the menu; it stops at its ends. */
    fun move(delta: Int) {
        synchronized(host.lock) {
            val c = cursor ?: return
            cursor = (c + delta).coerceIn(0, SettingsMenu.entries.size - 1)
            touchedAt = host.now()
            host.recompute()
        }
    }

    /**
     * Changes the line under the cursor: left (-1) or right (+1) along its scale; OK goes round
     * ([wrap]). The connection lines open the setup code.
     */
    fun change(direction: Int, wrap: Boolean) {
        val c = cursor ?: return
        touchedAt = host.now()
        val entry = SettingsMenu.entries[c]
        val s = host.settings
        when {
            // The connections, and the lights and values while none are set up, are set up on the phone.
            entry == MenuEntry.CONNECTION || entry == MenuEntry.MUSIC_ASSISTANT ||
                ((entry == MenuEntry.LIGHTS || entry == MenuEntry.LIGHTS_ONLY_ON) && !s.roomLights.isSet) ||
                (entry == MenuEntry.VALUES && s.ownValues.isEmpty()) -> {
                cursor = null
                host.requestSetup(true)
            }
            // The sleep timer is no setting: it lives until it has paused the music once.
            entry == MenuEntry.SLEEP_TIMER -> sleep.step(direction, wrap)
            // The language is kept by the system (or, before API 33, by the app on its own).
            entry == MenuEntry.LANGUAGE -> host.language = SettingsMenu.stepLanguage(host.language, direction, wrap)
            else -> SettingsMenu.step(entry, s, direction, wrap)?.let(host::applySettings)
        }
    }

    /** Closes a menu nobody has touched for [IDLE_MS] (called under the lock). */
    fun expire(t: Long) {
        if (cursor != null && t - touchedAt > IDLE_MS) cursor = null
    }

    /** The menu as it shows now, null while it is closed (called under the lock). */
    fun state(s: Settings, appearance: Appearance, track: TrackInfo?): MenuState? {
        val c = cursor ?: return null
        val ctx = MenuContext(
            link = host.ui.source.link,
            appearance = appearance,
            ma = host.ui.source.ma,
            sleep = sleep.choice,
            sleepAt = sleep.pausesAt,
            kind = track?.kind,
            language = host.language,
        )
        return MenuState(SettingsMenu.rows(s, ctx, host.texts), c)
    }

    companion object {
        /** An untouched menu closes after this long. */
        const val IDLE_MS = 60_000L
    }
}
