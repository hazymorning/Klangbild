package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import io.github.hazymorning.klangbild.render.plainPaint

internal actual fun cardPainter(): CardPainter = SkiaCardPainter()

/** Compose's canvas, through the perspective Android's Skia works out ([Perspective]). */
private class SkiaCardPainter : CardPainter {
    private val matrix = Matrix()
    private val perspective = Perspective()
    private val paint = plainPaint()
    private val layer = plainPaint()

    override fun moved(canvas: Canvas, image: ImageBitmap, x: Float, y: Float, alpha: Int, filter: ColorFilter?, blend: CardBlend) {
        matrix.reset()
        matrix[3, 0] = x
        matrix[3, 1] = y
        draw(canvas, image, alpha, filter, blend)
    }

    override fun mapped(canvas: Canvas, image: ImageBitmap, src: FloatArray, dst: FloatArray, alpha: Int, filter: ColorFilter?, blend: CardBlend) {
        perspective.map(src, dst, matrix)
        draw(canvas, image, alpha, filter, blend)
    }

    private fun draw(canvas: Canvas, image: ImageBitmap, alpha: Int, filter: ColorFilter?, blend: CardBlend) {
        paint.alpha = alpha / 255f
        paint.colorFilter = filter
        paint.blendMode = when (blend) {
            CardBlend.OVER -> BlendMode.SrcOver
            CardBlend.BENEATH -> BlendMode.DstOut
            CardBlend.ADD -> BlendMode.Plus
        }
        canvas.save()
        canvas.concat(matrix)
        canvas.drawImage(image, Offset.Zero, paint)
        canvas.restore()
    }

    override fun layer(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Int) {
        layer.alpha = alpha / 255f
        canvas.saveLayer(Rect(left, top, right, bottom), layer)
    }

    override fun restore(canvas: Canvas) {
        canvas.restore()
    }
}
