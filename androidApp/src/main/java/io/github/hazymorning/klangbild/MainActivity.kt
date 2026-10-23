package io.github.hazymorning.klangbild

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hazymorning.klangbild.app.RemoteKey
import io.github.hazymorning.klangbild.app.RemoteKeys
import io.github.hazymorning.klangbild.config.LaunchExtras
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.of
import io.github.hazymorning.klangbild.ui.DebugOverlay
import io.github.hazymorning.klangbild.ui.FrameStats
import io.github.hazymorning.klangbild.ui.KlangbildScreen
import io.github.hazymorning.klangbild.ui.RootHooks
import io.github.hazymorning.klangbild.ui.theme.AndroidGlyphs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one and only activity. Started by Home Assistant via
 * `am start -n io.github.hazymorning.klangbild/.MainActivity [--es entity …]`; while it runs,
 * new extras arrive through [onNewIntent] without a restart. What the remote's keys do is the
 * app's own ([RemoteKeys]); this hands them over.
 */
class MainActivity : ComponentActivity() {
    private val controller get() = graph.controller
    private val keys by lazy { RemoteKeys(controller) }
    private var view: ComposeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (a configuration change, the process restored) has applied its
        // launch's extras already: they would undo what was changed since.
        if (savedInstanceState == null) controller.applyLaunch(launchExtras(intent))
        // Predictive back (targetSdk 36) no longer delivers onBackPressed/KEYCODE_BACK.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!keys.back()) finish()
            }
        })
        // The composition lives while the activity is visible (see onStart/onStop).
        setContentView(ComposeView(this).also { view = it })
        // This TV as a speaker: its service starts while Klangbild is in sight (Android lets a
        // media service start only then) and ends itself when the setting goes off.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.settings.settings.map { it.speakerOn }.distinctUntilChanged().collect { on ->
                    if (on) startForegroundService(Intent(this@MainActivity, SpeakerService::class.java))
                }
            }
        }
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
            val hooks = remember {
                RootHooks(
                    positionOf = controller::positionOf,
                    peekUntil = keys.peekUntil,
                    fieldTime = controller::fieldTime,
                    landscapeClock = controller.landscapeClock,
                    glyphs = AndroidGlyphs,
                )
            }
            KlangbildScreen(controller.ui, spec, hooks) { ui ->
                if (ui.session.debugOverlay) {
                    // Frames are only counted while the overlay shows them.
                    DisposableEffect(Unit) {
                        FrameStats.attach(window)
                        onDispose { FrameStats.detach(window) }
                    }
                    DebugOverlay(FrameStats.stats, controller.sound)
                }
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
        controller.applyLaunch(launchExtras(intent))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = remoteKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (keys.down(key, event.repeatCount)) event.startTracking()
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val key = remoteKey(keyCode)
        return key != null && keys.longPress(key) || super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val key = remoteKey(keyCode)
        return key != null && keys.up(key, followed = event.isTracking, canceled = event.isCanceled) || super.onKeyUp(keyCode, event)
    }

    /** The remote's key that [keyCode] is, if the app answers it. */
    private fun remoteKey(keyCode: Int): RemoteKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.OK
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> RemoteKey.MENU
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteKey.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteKey.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteKey.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> RemoteKey.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> RemoteKey.PREVIOUS
        KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteKey.REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.FAST_FORWARD
        else -> null
    }

    /** The launch's extras, and the options of a `klangbild://` link ([LaunchExtras.fromLink]). */
    private fun launchExtras(intent: Intent?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        intent?.extras?.let { b ->
            for (key in b.keySet()) {
                @Suppress("DEPRECATION") // Bundle.get is the only call that reads an extra of any type.
                out[key] = b.get(key)
            }
        }
        intent?.data?.takeIf { it.scheme == LaunchExtras.DEEP_LINK_SCHEME && it.isHierarchical }?.let { uri ->
            out += LaunchExtras.fromLink(uri.queryParameterNames.associateWith { uri.getQueryParameter(it) })
        }
        return out
    }
}
