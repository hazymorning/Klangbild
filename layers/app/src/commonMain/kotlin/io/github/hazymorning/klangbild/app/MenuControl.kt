package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsMenu
import io.github.hazymorning.klangbild.domain.ReleaseNotes
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

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

    /** A group was just opened: the repeats of the key that opened it change nothing there. */
    @Volatile private var opening = false

    /** The new version's notes show, opened from the Update line. */
    @Volatile private var reading = false

    val isOpen: Boolean get() = cursor != null

    /** Opens the menu on its groups (an open one stays where it is). */
    fun open() {
        synchronized(host.lock) {
            if (cursor == null) {
                section = null
                reading = false
            }
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
            reading = false
            held = false
            host.recompute()
        }
    }

    /**
     * Back: from the new version's notes to the Update line, from a group to the groups, the
     * cursor on the group just left; from the groups out of the menu. Whether the menu is still open.
     */
    fun back(): Boolean = synchronized(host.lock) {
        val group = section
        if (reading) {
            reading = false
            cursor = UPDATE_LINE
            touchedAt = host.now()
        } else if (group == null) {
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

    /** How many lines the level shown has: with the notes, their points. */
    private fun lines(): Int = when {
        reading -> ReleaseNotes.points(host.update.notes).size.coerceAtLeast(1)
        else -> section?.let { SettingsMenu.entriesOf(it).size } ?: SettingsMenu.sections.size
    }

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
     * right (+1) along its scale; OK goes round ([wrap]). Set up on phone, the connection lines and the lines that
     * need something set up first, open the setup code. A held key ([repeat]) runs along a scale,
     * but never opens a group, nor goes on into the group it has just opened.
     */
    fun change(direction: Int, wrap: Boolean, repeat: Boolean = false) {
        // Read together: the menu may close by itself at any moment (expire).
        val (c, group) = synchronized(host.lock) {
            val c = cursor ?: return
            val group = section
            if (repeat && (group == null || opening)) return
            opening = false
            touchedAt = host.now()
            // OK on the notes installs the new version, and the Update line says how it goes.
            if (reading) {
                if (!wrap || repeat) return
                reading = false
                cursor = UPDATE_LINE
                host.recompute()
                host.update()
                return
            }
            if (group == null) {
                if (direction > 0) {
                    section = SettingsMenu.sections.getOrNull(c) ?: return
                    cursor = 0
                    held = false
                    opening = true
                    host.recompute()
                }
                return
            }
            c to group
        }
        val entry = SettingsMenu.entriesOf(group).getOrNull(c) ?: return
        val s = host.settings
        when {
            // Set up on phone, the connections, what needs them or lights or values first, and
            // another speaker than this TV are set up on the phone.
            entry == MenuEntry.PHONE_SETUP || entry == MenuEntry.CONNECTION || entry == MenuEntry.MUSIC_ASSISTANT ||
                SettingsMenu.opensSetup(entry, s, direction, wrap) -> {
                synchronized(host.lock) {
                    cursor = null
                    section = null
                }
                host.requestSetup(true)
            }
            // The sleep timer isn't stored with the settings. It lasts until it has paused the music once.
            entry == MenuEntry.SLEEP_TIMER -> sleep.step(direction, wrap)
            // The language is kept by the system (or, before API 33, by the app on its own).
            entry == MenuEntry.LANGUAGE -> host.language = SettingsMenu.stepLanguage(host.language, direction, wrap)
            // OK shows a new version's notes, or asks for one, also while Klangbild doesn't look
            // for them by itself. A held OK does it once.
            entry == MenuEntry.UPDATE -> if (wrap && !repeat) {
                if (host.update.available == null) {
                    host.update()
                } else {
                    synchronized(host.lock) {
                        reading = true
                        cursor = 0
                        held = false
                        host.recompute()
                    }
                }
            }
            else -> SettingsMenu.step(entry, s, direction, wrap)?.let(host::applySettings)
        }
    }

    /** Closes a menu nobody has touched for [IDLE_MS] (called under the lock). */
    fun expire(t: Long) {
        if (cursor != null && t - touchedAt > IDLE_MS) {
            cursor = null
            section = null
            reading = false
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
            speaker = host.speaker,
            speakerName = host.ui.source.player?.friendlyName?.takeIf { host.ui.source.speaker == s.entityId },
            update = host.update,
            notifyFailed = host.notifyFailed,
        )
        val group = section
        val rows = if (group == null) SettingsMenu.groups(s, ctx, host.texts) else SettingsMenu.rows(s, ctx, host.texts, group)
        val notes = host.update.available?.takeIf { reading }?.let { MenuNotes(it.removePrefix("v"), ReleaseNotes.points(host.update.notes)) }
        return MenuState(rows, c, held, group, notes)
    }

    companion object {
        /** An untouched menu closes after this long. */
        const val IDLE_MS = 60_000L

        /** Where the Update line stands in System. */
        private val UPDATE_LINE = SettingsMenu.entriesOf(MenuSection.SYSTEM).indexOf(MenuEntry.UPDATE)
    }
}
