package io.github.hazymorning.klangbild.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/** A bitmap of [pixels], ARGB row by row and not premultiplied, [width] wide and [height] high. */
expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

/**
 * A paint as the platform's own comes, without the dithering Compose turns on for its paints:
 * smooth edges and filtered bitmaps, and the exact colors of what it draws.
 */
expect fun plainPaint(): Paint
