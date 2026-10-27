package io.github.hazymorning.klangbild.ui.flow

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.nativeCanvas

internal actual fun cardPainter(): CardPainter = AndroidCardPainter()

/** Android's canvas with its own matrix and paint, which Compose's canvas would make anew for every texture. */
private class AndroidCardPainter : CardPainter {
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)

    override fun drawTranslated(canvas: Canvas, image: ImageBitmap, x: Float, y: Float, alpha: Int, filter: ColorFilter?, blend: CardBlend) {
        matrix.setTranslate(x, y)
        draw(canvas, image, alpha, filter, blend)
    }

    override fun drawMapped(canvas: Canvas, image: ImageBitmap, src: FloatArray, dst: FloatArray, alpha: Int, filter: ColorFilter?, blend: CardBlend) {
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        draw(canvas, image, alpha, filter, blend)
    }

    private fun draw(canvas: Canvas, image: ImageBitmap, alpha: Int, filter: ColorFilter?, blend: CardBlend) {
        paint.alpha = alpha
        paint.colorFilter = filter?.asAndroidColorFilter()
        paint.xfermode = when (blend) {
            CardBlend.OVER -> null
            CardBlend.ERASE -> erase
            CardBlend.ADD -> add
        }
        canvas.nativeCanvas.drawBitmap(image.asAndroidBitmap(), matrix, paint)
        paint.xfermode = null
    }

    override fun saveLayer(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Int) {
        bounds.set(left, top, right, bottom)
        canvas.nativeCanvas.saveLayerAlpha(bounds, alpha)
    }

    override fun restore(canvas: Canvas) {
        canvas.nativeCanvas.restore()
    }
}
