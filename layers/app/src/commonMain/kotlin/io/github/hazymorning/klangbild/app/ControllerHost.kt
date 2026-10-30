package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.UpdateInfo
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope

/**
 * What the parts of [NowPlayingController] (the menus, seeking, the sleep timer) need from it.
 * Keys arrive on the main thread, ticks and Home Assistant's reports on background threads, so
 * whatever changes together changes under [lock], the controller's. A field another thread
 * reads on its own (the main thread asking whether a menu is open) is also `@Volatile`.
 */
internal interface ControllerHost {
    val lock: SynchronizedObject
    val scope: CoroutineScope
    val now: () -> Long

    /** The settings as they are now, local changes included. */
    val settings: Settings

    /** What the screen shows now. */
    val ui: UiState

    /** The connection to Home Assistant, while there is one. */
    val source: NowPlayingSource?

    /** The words in the language shown. */
    val texts: Texts

    /** The app's log. */
    val log: Log

    /** The language chosen. */
    var language: AppLanguage

    /** How this TV stands as a speaker in Music Assistant. */
    val speaker: SpeakerLink

    /** What Klangbild knows of its own new versions. */
    val update: UpdateInfo

    /** Home Assistant didn't take this TV's registration for notifications. */
    val notifyFailed: Boolean get() = false

    /** OK on the menu's Update line: installs a new version, or asks for one now. */
    fun update()

    /** Works out the screen again (takes [lock]). */
    fun recompute()

    /** Shows the menu's cursor where it is now, nothing else changed (takes [lock]). */
    fun showMenu()

    /** Opens the settings menu on its groups. */
    fun openMenu()

    /** A change from one of the menus: applied at once and written behind it; ignored until the stored settings are in. */
    fun applySettings(updated: Settings)

    fun send(command: RemoteCommand)

    fun requestSetup(show: Boolean)
}
