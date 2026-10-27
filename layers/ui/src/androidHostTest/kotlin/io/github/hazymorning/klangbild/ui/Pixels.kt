package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import kotlin.math.abs

/** A captured picture's pixels as ARGB, read in one call (reading them one by one is slow). */
class Pixels(bitmap: Bitmap) {
    val width = bitmap.width
    val height = bitmap.height
    private val argb = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    /** How bright the pixel at ([x], [y]) is: its brightest channel (0 to 255). */
    fun level(x: Int, y: Int): Int = level(this[x, y])

    /** The brightest [level] from ([x0], [y0]) to ([x1], [y1]), the ends left out. */
    fun brightest(x0: Int = 0, y0: Int = 0, x1: Int = width, y1: Int = height): Int {
        var brightest = 0
        for (y in y0 until y1) for (x in x0 until x1) brightest = maxOf(brightest, level(x, y))
        return brightest
    }

    /** The mean [level] from ([x0], [y0]) to ([x1], [y1]), the ends left out. */
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var sum = 0L
        for (y in y0 until y1) for (x in x0 until x1) sum += level(x, y)
        return sum.toFloat() / ((x1 - x0) * (y1 - y0))
    }

    companion object {
        /** How bright the ARGB pixel [p] is: its brightest channel (0 to 255). */
        fun level(p: Int): Int = maxOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)

        /** How far the ARGB pixels [p] and [q] lie apart: the largest difference of a channel. */
        fun apart(p: Int, q: Int): Int =
            maxOf(abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)), abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)), abs((p and 0xFF) - (q and 0xFF)))
    }
}

/** What the rule's content shows now. */
fun ComposeContentTestRule.pixels(): Pixels = Pixels(onRoot().captureToImage().asAndroidBitmap())
