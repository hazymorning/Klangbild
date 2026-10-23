package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint

/** Draws the first [count] points of [points] (x and y each) with [paint], in one call. */
internal expect fun Canvas.drawPointBatch(points: FloatArray, count: Int, paint: Paint)

/** Draws the first [count] lines of [lines] (two points each) with [paint], in one call. */
internal expect fun Canvas.drawLineBatch(lines: FloatArray, count: Int, paint: Paint)
