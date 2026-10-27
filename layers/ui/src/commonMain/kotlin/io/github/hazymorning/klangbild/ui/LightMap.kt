package io.github.hazymorning.klangbild.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.render.ImageOps
import io.github.hazymorning.klangbild.render.LinearImage
import io.github.hazymorning.klangbild.render.imageBitmapOf

/**
 * A grayscale map of light, [w] × [h] px, to be drawn multiplied by [tint]: [light] gives each
 * pixel's light (linear, 0 to 1). It is dithered by about one level of the tinted light, so no
 * bands show, and it is black where the light is too faint to see.
 */
internal inline fun lightMap(w: Int, h: Int, seed: Int, tint: Int, light: (x: Int, y: Int) -> Float): ImageBitmap {
    val img = LinearImage(w, h)
    for (y in 0 until h) for (x in 0 until w) {
        val value = light(x, y)
        val o = y * w + x
        img.r[o] = value
        img.g[o] = value
        img.b[o] = value
    }
    val px = ImageOps.toArgbDithered(img, seed = seed, ditherLsb = ImageOps.ditherFor(tint), blackBelow = 0.0005f)
    return imageBitmapOf(px, w, h)
}
