package io.github.hazymorning.klangbild

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hazymorning.klangbild.app.graph
import io.github.hazymorning.klangbild.config.LaunchExtras
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.DebugOverlay
import io.github.hazymorning.klangbild.ui.FrameStats
import io.github.hazymorning.klangbild.ui.KlangbildRoot
import io.github.hazymorning.klangbild.ui.RootHooks
import kotlinx.coroutines.launch

/**
 * The one and only activity. Started by Home Assistant via
 * `am start -n io.github.hazymorning.klangbild/.MainActivity [--es entity …]`; while it runs,
 * new extras arrive through [onNewIntent] without a restart.
 */
class MainActivity : ComponentActivity() {
    private val controller get() = graph.controller
    private val peekUntil = mutableLongStateOf(0L)
    private var view: ComposeView? = null
    private var centerLongPressed = false
    private var sideLongPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (a configuration change, the process restored) has applied its
        // launch's extras already: they would undo what was changed since.
        if (savedInstanceState == null) controller.applyLaunch(LaunchExtras.fromIntent(intent))
        // Predictive back (targetSdk 36) no longer delivers onBackPressed/KEYCODE_BACK.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        // The composition lives while the activity is visible (see onStart/onStop).
        setContentView(ComposeView(this).also { view = it })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.ui.collect { ui ->
                    if (ui.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    if (ui.asleep && ui.sleepAction == SleepAction.CLOSE) finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val spec = ScreenSpec.of(resources.displayMetrics)
        controller.attach(spec)
        view?.setContent {
            val ui by controller.ui.collectAsStateWithLifecycle()
            val hooks = remember { RootHooks(positionNow = controller::positionNow, peekUntil = peekUntil, fieldTime = controller::fieldTime, landscapeClock = controller.landscapeClock) }
            KlangbildRoot(ui, spec, hooks)
            if (ui.session.debugOverlay) {
                // Frames are only counted while the overlay shows them.
                DisposableEffect(Unit) {
                    FrameStats.attach(window)
                    onDispose { FrameStats.detach(window) }
                }
                DebugOverlay()
            }
        }
    }

    override fun onStop() {
        controller.detach()
        // Out of sight nothing is kept: the screens' scenes and bitmaps go with the composition,
        // and coming back fades in from black, built anew.
        view?.disposeComposition()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.applyLaunch(LaunchExtras.fromIntent(intent))
    }

    private fun peek() {
        peekUntil.longValue = System.currentTimeMillis() + PEEK_MS
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The quick settings take their keys first; they leave the landscape as it is.
        if (controller.quickOpen && quickKey(keyCode)) return true
        val setup = controller.ui.value.screen is Screen.Setup
        val quickKeys = !setup && !controller.volumeKeys && !controller.menuOpen
        // Down opens the quick settings over whatever shows (the landscape too); up brings the
        // landscape or leaves it. Every other key starts the landscape's delays again: one
        // that came by itself goes, a chosen one stays, and the key does its work either way.
        val upDown = keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (event.repeatCount == 0 && keyCode in REMOTE_KEYS && !(quickKeys && upDown)) controller.interaction()
        if (controller.menuOpen && menuKey(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) {
                    centerLongPressed = false
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // A held key repeats: it changes the track once, not twenty times.
                if (event.repeatCount == 0 && onStage()) {
                    if (controller.spokenKeys) {
                        // A book or an episode: a press jumps (on release), a held key goes by chapters.
                        sideLongPressed = false
                        event.startTracking()
                    } else {
                        controller.send(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
                        peek()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event.repeatCount == 0 && onStage()) {
                    controller.send(if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) RemoteCommand.PREVIOUS else RemoteCommand.NEXT)
                    peek()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.repeatCount == 0 && onStage()) {
                    controller.skip(if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -1 else +1)
                    peek()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) {
                    controller.send(
                        when (keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteCommand.PLAY
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteCommand.PAUSE
                            else -> RemoteCommand.PLAY_PAUSE
                        },
                    )
                    peek()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!setup && controller.volumeKeys) {
                    controller.send(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) RemoteCommand.VOLUME_UP else RemoteCommand.VOLUME_DOWN)
                } else if (event.repeatCount == 0 && quickKeys) {
                    // Down: the quick settings rise from the bottom edge. Up: the landscape comes,
                    // and up again (or back) leaves it.
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) controller.openQuick() else controller.toggleLandscape()
                    peek()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                controller.closeQuick()
                controller.openMenu()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Keys while the quick settings are open (a row along the bottom edge): left and right choose
     * one, OK (on release) changes it or does what it says; down again closes the row, as does
     * up, which moves away from it; Menu opens the settings. False lets the key through (media
     * keys, back).
     */
    private fun quickKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> controller.quickMove(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> controller.quickMove(+1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> controller.closeQuick()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> controller.touchQuick()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                controller.closeQuick()
                controller.openMenu()
            }
            else -> return false
        }
        return true
    }

    /** Keys while the settings menu is open; false lets the key through (media keys). */
    private fun menuKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> controller.menuMove(-1, held = event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> controller.menuMove(+1, held = event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> controller.menuChange(-1, wrap = false, repeat = event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> controller.menuChange(+1, wrap = false, repeat = event.repeatCount > 0)
            // OK acts on release, like everywhere else (a long press must not also change a line).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Unit
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> controller.closeMenu()
            else -> return false
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            centerLongPressed = true
            if (!controller.menuOpen) controller.openMenu()
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && !controller.menuOpen) {
            sideLongPressed = true
            controller.jump(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1)
            peek()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!centerLongPressed && !event.isCanceled) onCenterPressed()
            centerLongPressed = false
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Only a press that started a jump (spoken word) acts on release.
            if (event.isTracking && !sideLongPressed && !event.isCanceled && onStage()) {
                controller.skip(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1)
                peek()
            }
            sideLongPressed = false
            return true
        }
        // Up or down held to run through the settings is let go: the explanation comes back.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) controller.menuRelease()
        return super.onKeyUp(keyCode, event)
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
     * Back closes the app, or just what lies on top of the music: the settings (a group goes back
     * to the groups first), the quick menu, the landscape, the setup code.
     */
    private fun onBack() {
        val s = controller.ui.value.screen
        when {
            controller.menuOpen -> controller.menuBack()
            controller.quickOpen -> controller.closeQuick()
            // The landscape goes; the app stays.
            controller.hideLandscape() -> Unit
            s is Screen.Setup && s.reason == SetupReason.REQUESTED -> controller.requestSetup(false)
            else -> finish()
        }
    }

    private fun onStage() = controller.ui.value.screen is Screen.Stage

    companion object {
        const val PEEK_MS = 6_000L

        /** The remote's keys: each starts the landscape's delays again. */
        private val REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

    }
}
