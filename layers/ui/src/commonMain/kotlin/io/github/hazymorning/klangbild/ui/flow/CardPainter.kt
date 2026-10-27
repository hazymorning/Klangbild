package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap

/** How a texture meets what lies beneath it: laid over, erasing it by the texture's alpha, or adding its light. */
internal enum class CardBlend { OVER, ERASE, ADD }

/**
 * Draws the cards' textures through their perspective, as the platform draws them fastest,
 * keeping what it needs between frames.
 */
internal interface CardPainter {
    /** Draws [image] moved by [x] and [y] (whole pixels), at [alpha] (0 to 255), lit by [filter] and blended as [blend] says. */
    fun drawTranslated(canvas: Canvas, image: ImageBitmap, x: Float, y: Float, alpha: Int, filter: ColorFilter?, blend: CardBlend)

    /**
     * Draws [image] mapped so that the corners [src] land on [dst] (x and y of four corners
     * each, clockwise from the top left). A quad without area keeps the mapping before.
     */
    fun drawMapped(canvas: Canvas, image: ImageBitmap, src: FloatArray, dst: FloatArray, alpha: Int, filter: ColorFilter?, blend: CardBlend)

    /** Starts a layer over [left] to [bottom] that is laid over at [alpha] (0 to 255) when [restore] ends it. */
    fun saveLayer(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Int)

    /** Ends the layer [saveLayer] started. */
    fun restore(canvas: Canvas)
}

/** The platform's [CardPainter]. */
internal expect fun cardPainter(): CardPainter
