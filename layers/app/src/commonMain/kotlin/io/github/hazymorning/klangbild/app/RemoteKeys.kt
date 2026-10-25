package io.github.hazymorning.klangbild.app

import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import kotlin.time.Clock

/** The keys of the TV's remote that the app answers. */
enum class RemoteKey { UP, DOWN, LEFT, RIGHT, OK, MENU, PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, REWIND, FAST_FORWARD }

/**
 * What the remote's keys do. The platform hands each key over as it goes down ([down], with how
 * often it has repeated since), as it is held long ([longPress], for a key [down] asked to be
 * followed) and as it is let go ([up]), and the way back ([back]).
 */
class RemoteKeys(private val controller: NowPlayingController, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val peek: MutableLongState = mutableLongStateOf(0L)

    /** Local time until which hidden parts (time, place, progress) show again after a key press. */
    val peekUntil: State<Long> get() = peek

    private var centerLongPressed = false
    private var sideLongPressed = false

    /** Left or right went down on a book or an episode: let go it jumps, held it goes by chapters. */
    private var sideSpoken = false

    /** Since when left or right has been held on a song (local time), while it seeks. */
    private var holdingSince: Long? = null

    private fun peek() {
        peek.longValue = now() + PEEK_MS
    }

    /** [key] going down, [repeat] times repeated while held. True asks the platform to follow it for a long press and its release. */
    fun down(key: RemoteKey, repeat: Int): Boolean {
        // The quick settings take their keys first.
        if (controller.quickOpen && quickKey(key)) return false
        val setup = controller.ui.value.screen is Screen.Setup
        val quickKeys = !setup && !controller.volumeKeys && !controller.menuOpen
        if (controller.menuOpen && menuKey(key, repeat)) return false
        when (key) {
            RemoteKey.OK -> {
                if (repeat == 0) {
                    centerLongPressed = false
                    return true
                }
            }
            RemoteKey.LEFT, RemoteKey.RIGHT -> {
                // A held key repeats: it changes the track once, not twenty times.
                if (repeat == 0 && onStage()) {
                    sideLongPressed = false
                    holdingSince = null
                    // A book or an episode: a press jumps (on release), a held key goes by chapters.
                    // A song: a press changes it (on release), a held key seeks through it.
                    sideSpoken = controller.spokenKeys
                    if (sideSpoken || controller.holdKeys) return true
                    controller.send(if (key == RemoteKey.LEFT) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
                    peek()
                } else if (repeat > 0) {
                    // Each repeat while held moves the place sought on.
                    holdingSince?.let { since ->
                        controller.hold(if (key == RemoteKey.LEFT) -1 else +1, now() - since)
                        peek()
                    }
                }
            }
            RemoteKey.PREVIOUS, RemoteKey.NEXT -> {
                if (repeat == 0 && onStage()) {
                    controller.send(if (key == RemoteKey.PREVIOUS) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
                    peek()
                }
            }
            RemoteKey.REWIND, RemoteKey.FAST_FORWARD -> {
                if (repeat == 0 && onStage()) {
                    controller.skip(if (key == RemoteKey.REWIND) -1 else +1)
                    peek()
                }
            }
            RemoteKey.PLAY_PAUSE, RemoteKey.PLAY, RemoteKey.PAUSE -> {
                if (repeat == 0) {
                    controller.send(
                        when (key) {
                            RemoteKey.PLAY -> RemoteCommand.PLAY
                            RemoteKey.PAUSE -> RemoteCommand.PAUSE
                            else -> RemoteCommand.PLAY_PAUSE
                        },
                    )
                    peek()
                }
            }
            RemoteKey.UP, RemoteKey.DOWN -> {
                if (!setup && controller.volumeKeys) {
                    controller.send(if (key == RemoteKey.UP) RemoteCommand.VOLUME_UP else RemoteCommand.VOLUME_DOWN)
                } else if (repeat == 0 && quickKeys) {
                    // Down: the quick settings rise from the bottom edge. Up: the settings open.
                    if (key == RemoteKey.DOWN) controller.openQuick() else controller.openMenu()
                    peek()
                }
            }
            RemoteKey.MENU -> {
                controller.closeQuick()
                controller.openMenu()
            }
        }
        return false
    }

    /**
     * Keys while the quick settings are open (a row along the bottom edge): left and right choose
     * one, OK (on release) changes it or does what it says; down again closes the row, as does
     * up, which moves away from it; Menu opens the settings. False lets the key through (media
     * keys).
     */
    private fun quickKey(key: RemoteKey): Boolean {
        when (key) {
            RemoteKey.LEFT -> controller.quickMove(-1)
            RemoteKey.RIGHT -> controller.quickMove(+1)
            RemoteKey.UP, RemoteKey.DOWN -> controller.closeQuick()
            RemoteKey.OK -> controller.touchQuick()
            RemoteKey.MENU -> {
                controller.closeQuick()
                controller.openMenu()
            }
            else -> return false
        }
        return true
    }

    /** Keys while the settings menu is open; false lets the key through (media keys). */
    private fun menuKey(key: RemoteKey, repeat: Int): Boolean {
        when (key) {
            RemoteKey.UP -> controller.menuMove(-1, held = repeat > 0)
            RemoteKey.DOWN -> controller.menuMove(+1, held = repeat > 0)
            RemoteKey.LEFT -> controller.menuChange(-1, wrap = false, repeat = repeat > 0)
            RemoteKey.RIGHT -> controller.menuChange(+1, wrap = false, repeat = repeat > 0)
            // OK acts on release, like everywhere else (a long press must not also change a line).
            RemoteKey.OK -> Unit
            RemoteKey.MENU -> controller.closeMenu()
            else -> return false
        }
        return true
    }

    /** [key] held long after [down] asked to follow it. False lets the platform go on with it. */
    fun longPress(key: RemoteKey): Boolean {
        if (key == RemoteKey.OK) {
            centerLongPressed = true
            if (!controller.menuOpen) controller.openMenu()
            return true
        }
        if ((key == RemoteKey.LEFT || key == RemoteKey.RIGHT) && !controller.menuOpen) {
            sideLongPressed = true
            val direction = if (key == RemoteKey.LEFT) -1 else +1
            if (sideSpoken) {
                controller.jump(direction)
            } else {
                holdingSince = now()
                controller.hold(direction, 0)
            }
            peek()
            return true
        }
        return false
    }

    /**
     * [key] let go: [followed] if [down] asked to follow it, [canceled] if the platform took it
     * back. False lets the platform go on with it.
     */
    fun up(key: RemoteKey, followed: Boolean, canceled: Boolean): Boolean {
        if (key == RemoteKey.OK) {
            if (!centerLongPressed && !canceled) onCenterPressed()
            centerLongPressed = false
            return true
        }
        if (key == RemoteKey.LEFT || key == RemoteKey.RIGHT) {
            // Only a press followed from its start acts on release: a jump, or another song.
            if (followed && !sideLongPressed && !canceled && onStage()) {
                if (sideSpoken) {
                    controller.skip(if (key == RemoteKey.LEFT) -1 else +1)
                } else {
                    controller.send(if (key == RemoteKey.LEFT) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
                }
                peek()
            }
            // A hold sends the place it reached.
            if (holdingSince != null) controller.letGo()
            holdingSince = null
            sideLongPressed = false
            return true
        }
        // Up or down held to run through the settings is let go: the explanation comes back.
        if (key == RemoteKey.UP || key == RemoteKey.DOWN) controller.menuRelease()
        return false
    }

    private fun onCenterPressed() {
        if (controller.menuOpen) {
            controller.menuChange(+1, wrap = true)
            return
        }
        if (controller.quickOpen) {
            controller.quickChange()
            return
        }
        when (val s = controller.ui.value.screen) {
            is Screen.Setup -> when (s.reason) {
                SetupReason.FIRST_RUN -> Unit
                SetupReason.REQUESTED -> controller.requestSetup(false)
                SetupReason.AUTH_FAILED -> Unit
            }
            is Screen.Stage -> {
                controller.send(RemoteCommand.PLAY_PAUSE)
                peek()
            }
            is Screen.Rest -> controller.send(RemoteCommand.PLAY_PAUSE)
        }
    }

    /**
     * Back closes just what lies on top of the music: the settings (a group goes back to the
     * groups first), the quick menu, the setup code. False when nothing does, and the app closes.
     */
    fun back(): Boolean {
        val s = controller.ui.value.screen
        when {
            controller.menuOpen -> controller.menuBack()
            controller.quickOpen -> controller.closeQuick()
            s is Screen.Setup && s.reason == SetupReason.REQUESTED -> controller.requestSetup(false)
            else -> return false
        }
        return true
    }

    private fun onStage() = controller.ui.value.screen is Screen.Stage

    private companion object {
        const val PEEK_MS = 6_000L
    }
}
