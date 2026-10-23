package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import io.github.hazymorning.klangbild.ui.R

/**
 * The faces as Android resources, made once for the app: Android loads them without a
 * composition. Android sets a resource font's axes on the typeface it loaded, which keeps the
 * file's own weight as its style, so what the faces don't cover falls back to the system's fonts
 * at their regular weight.
 */
private val androidFaces by lazy {
    Faces(faceFonts { face, weight, settings -> Font(if (face == Face.TITLE) R.font.jost else R.font.dmsans, weight, variationSettings = settings) })
}

@Composable
actual fun rememberFaces(): Faces = androidFaces
