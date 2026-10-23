package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.LoadedFont
import io.github.hazymorning.klangbild.ui.resources.Res
import io.github.hazymorning.klangbild.ui.resources.dmsans
import io.github.hazymorning.klangbild.ui.resources.jost
import org.jetbrains.compose.resources.Font

/**
 * The faces as Compose resources, where Compose draws with Skia: the same files as Android's,
 * each read once and shared by all its weights and optical sizes. A browser reads them after the
 * first frames, which show the system's fonts, and the faces change as each file comes.
 */
@Composable
actual fun rememberFaces(): Faces {
    val title = Font(Res.font.jost)
    val text = Font(Res.font.dmsans)
    return remember(title, text) {
        Faces(
            faceFonts { face, weight, settings ->
                val file = if (face == Face.TITLE) title else text
                // Skia keeps a typeface for each name and weight, so every cut has a name of its own.
                val name = "$face ${settings.settings.joinToString(" ") { "${it.axisName} ${it.toVariationValue(null)}" }}"
                if (file is LoadedFont) Font(name, file.data, weight, variationSettings = settings) else file
            },
        )
    }
}
