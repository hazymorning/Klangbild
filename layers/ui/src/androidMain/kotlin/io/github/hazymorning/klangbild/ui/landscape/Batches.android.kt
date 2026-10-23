package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.nativePaint

// Android's canvas draws part of an array; Compose's would draw point by point.

internal actual fun Canvas.drawPointBatch(points: FloatArray, count: Int, paint: Paint) =
    nativeCanvas.drawPoints(points, 0, count * 2, paint.nativePaint)

internal actual fun Canvas.drawLineBatch(lines: FloatArray, count: Int, paint: Paint) =
    nativeCanvas.drawLines(lines, 0, count * 4, paint.nativePaint)
