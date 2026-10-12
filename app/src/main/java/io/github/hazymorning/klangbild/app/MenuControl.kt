package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsMenu
import io.github.hazymorning.klangbild.domain.TrackInfo

/**
 * The settings menu while it is open: the group opened (none: the groups themselves), which line
 * the cursor is on, and what a key does to that line. An untouched menu closes by itself after
 * [IDLE_MS]: it must not stand still on an OLED.
 */
internal class MenuControl(private val host: ControllerHost, private val sleep: SleepControl) {
    @Volatile private var cursor: Int? = null
    @Volatile private var section: MenuSection? = null
    @Volatile private var touchedAt = 0L

    /** Up or down is held: the cursor runs on with the key's repeats. */
    @Volatile private var held = false

    val isOpen: Boolean get() = cursor != null

    /** Opens the menu on its groups (an open one stays where it is). */
    fun open() {
        synchronized(host.lock) {
            if (cursor == null) section = null
            cursor = cursor ?: 0
            held = false
            touchedAt = host.now()
            host.recompute()
        }
    }

    fun close() {
        synchronized(host.lock) {
            cursor = null
            section = null
            held = false
            host.recompute()
        }
    }

    /**
     * Back: from a group to the groups, the cursor on the group just left; from the groups out of
     * the menu. Whether the menu is still open.
     */
    fun back(): Boolean = synchronized(host.lock) {
        val group = section
        if (group == null) {
            cursor = null
        } else {
            section = null
            cursor = SettingsMenu.sections.indexOf(group)
            touchedAt = host.now()
        }
        held = false
        host.recompute()
        cursor != null
    }

    /** How many lines the level shown has. */
    private fun lines(): Int = section?.let { SettingsMenu.entriesOf(it).size } ?: SettingsMenu.sections.size

    /** Up (-1) or down (+1) through the menu; it stops at its ends. [held]: the key repeats. */
    fun move(delta: Int, held: Boolean = false) {
        synchronized(host.lock) {
            val c = cursor ?: return
            cursor = (c + delta).coerceIn(0, lines() - 1)
            this.held = held
            touchedAt = host.now()
            host.showMenu()
        }
    }

    /** The key held to run through the menu is let go. */
    fun release() {
        synchronized(host.lock) {
            if (!held) return
            held = false
            host.showMenu()
        }
    }

    /** [shown] with the cursor where it is now (called under the lock). */
    fun moved(shown: MenuState): MenuState = shown.copy(cursor = cursor ?: shown.cursor, held = held)

    /**
     * OK or right on a group opens it, on its first line. On a setting, changes it: left (-1) or
     * right (+1) along its scale; OK goes round ([wrap]). The connection lines, and the lines that
     * need something set up first, open the setup code.
     */
    fun change(direction: Int, wrap: Boolean) {
        val c = cursor ?: return
        touchedAt = host.now()
        val group = section
        if (group == null) {
            if (direction > 0) {
                synchronized(host.lock) {
                    section = SettingsMenu.sections[c]
                    cursor = 0
                    held = false
                    host.recompute()
                }
            }
            return
        }
        val entry = SettingsMenu.entriesOf(group)[c]
        val s = host.settings
        when {
            // The connections, and what needs them or lights or values first, are set up on the phone.
            entry == MenuEntry.CONNECTION || entry == MenuEntry.MUSIC_ASSISTANT || SettingsMenu.needsSetup(entry, s) -> {
                cursor = null
                section = null
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
        if (cursor != null && t - touchedAt > IDLE_MS) {
            cursor = null
            section = null
        }
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
        val group = section
        val rows = if (group == null) SettingsMenu.groups(host.texts) else SettingsMenu.rows(s, ctx, host.texts, group)
        return MenuState(rows, c, held, group)
    }

    companion object {
        /** An untouched menu closes after this long. */
        const val IDLE_MS = 60_000L
    }
}
