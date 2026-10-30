package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.theme.LocalFaces
import io.github.hazymorning.klangbild.ui.theme.rememberFaces
import kotlinx.coroutines.flow.StateFlow

/**
 * The app on a screen: [ui] as it changes, on the root ([KlangbildRoot]), with the typefaces
 * loaded once for all of it. [keyHint] false (a screensaver, which any key ends) hides the hint
 * that tells which key opens the quick settings. [overlay] lies on top, given the state.
 *
 * While no one is in the room ([UiState.dark]) the whole picture fades to black, slowly, and
 * comes back in about a second once someone is.
 */
@Composable
fun KlangbildScreen(ui: StateFlow<UiState>, screen: ScreenSpec, hooks: RootHooks, keyHint: Boolean = true, overlay: @Composable (UiState) -> Unit = {}) {
    CompositionLocalProvider(LocalFaces provides rememberFaces()) {
        val state by ui.collectAsStateWithLifecycle()
        val shown by animateFloatAsState(
            targetValue = if (state.dark) 0f else 1f,
            animationSpec = tween(if (state.dark) DARKEN_MS else BRIGHTEN_MS, easing = Motion.Dimmer),
            label = "presence",
        )
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            KlangbildRoot(if (keyHint) state else state.copy(downHint = false), screen, hooks, Modifier.graphicsLayer { alpha = shown })
            overlay(state)
        }
    }
}

/** How long the picture takes to fade to black once no one is in the room (ms). */
private const val DARKEN_MS = 4_000

/** How long it takes to come back once someone is (ms). */
private const val BRIGHTEN_MS = 1_000
