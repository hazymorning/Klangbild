package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode

// Compose's canvas draws whole arrays, so a batch is drawn from a copy just as long as it is.

internal actual fun Canvas.drawPointBatch(points: FloatArray, count: Int, paint: Paint) =
    drawRawPoints(PointMode.Points, points.copyOf(count * 2), paint)

internal actual fun Canvas.drawLineBatch(lines: FloatArray, count: Int, paint: Paint) =
    drawRawPoints(PointMode.Lines, lines.copyOf(count * 4), paint)
