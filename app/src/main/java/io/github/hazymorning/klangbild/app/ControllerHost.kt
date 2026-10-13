package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.CoroutineScope

/**
 * What the parts of [NowPlayingController] (the menus, seeking, the sleep timer) need from it.
 * They all lock on [lock], the controller itself: keys arrive on the main thread, ticks and
 * Home Assistant's reports on background threads.
 */
internal interface ControllerHost {
    val lock: Any
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

    /** The language chosen. */
    var language: AppLanguage

    /** Works out the screen again (takes [lock]). */
    fun recompute()

    /** Shows the menu's cursor where it is now, nothing else changed (takes [lock]). */
    fun showMenu()

    /** Opens the settings menu on its groups. */
    fun openMenu()

    /** A change from one of the menus: shown at once, written behind it. */
    fun applySettings(updated: Settings)

    fun send(command: RemoteCommand)

    fun requestSetup(show: Boolean)

    fun showLandscape()

    /** Leaves the landscape; whether it was showing. */
    fun hideLandscape(): Boolean
}
