package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.hazymorning.klangbild.ui.resources.Res
import io.github.hazymorning.klangbild.ui.resources.dmsans
import io.github.hazymorning.klangbild.ui.resources.jost

/** The faces as Compose resources, where Compose draws with Skia: the same files as Android's. */
@Composable
internal actual fun faceFont(face: Face, weight: FontWeight, settings: FontVariation.Settings): Font =
    org.jetbrains.compose.resources.Font(if (face == Face.TITLE) Res.font.jost else Res.font.dmsans, weight, variationSettings = settings)
