package io.github.hazymorning.klangbild.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** The app's texts for the composition below, set at the root from the language chosen. */
val LocalTexts = staticCompositionLocalOf<Texts?> { null }

/** The texts in the language shown; a screen rendered on its own takes its context's language. */
@Composable
fun texts(): Texts {
    LocalTexts.current?.let { return it }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { Texts.of(context) }
}
