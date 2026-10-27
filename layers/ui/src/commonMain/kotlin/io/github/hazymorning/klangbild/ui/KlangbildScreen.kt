package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
 */
@Composable
fun KlangbildScreen(ui: StateFlow<UiState>, screen: ScreenSpec, hooks: RootHooks, keyHint: Boolean = true, overlay: @Composable (UiState) -> Unit = {}) {
    CompositionLocalProvider(LocalFaces provides rememberFaces()) {
        val state by ui.collectAsStateWithLifecycle()
        KlangbildRoot(if (keyHint) state else state.copy(downHint = false), screen, hooks)
        overlay(state)
    }
}
