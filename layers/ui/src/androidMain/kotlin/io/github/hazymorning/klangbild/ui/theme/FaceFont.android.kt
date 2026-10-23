package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.hazymorning.klangbild.ui.R

/**
 * The faces as Android resources. Android sets a resource font's axes on the typeface it loaded,
 * which keeps the file's own weight as its style, so what the faces don't cover falls back to the
 * system's fonts at their regular weight.
 */
@Composable
internal actual fun faceFont(face: Face, weight: FontWeight, settings: FontVariation.Settings): Font =
    Font(if (face == Face.TITLE) R.font.jost else R.font.dmsans, weight, variationSettings = settings)
