package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.hazymorning.klangbild.ui.resources.Res
import io.github.hazymorning.klangbild.ui.resources.dmsans
import io.github.hazymorning.klangbild.ui.resources.jost
import org.jetbrains.compose.resources.Font

/**
 * The faces as Compose resources, where Compose draws with Skia: the same files as Android's. A
 * browser loads them after the first frames, and the faces change once they have.
 */
@Composable
actual fun rememberFaces(): Faces {
    val fonts = faceFonts { face, weight, settings -> Font(if (face == Face.TITLE) Res.font.jost else Res.font.dmsans, weight, variationSettings = settings) }
    return remember(fonts) { Faces(fonts) }
}
