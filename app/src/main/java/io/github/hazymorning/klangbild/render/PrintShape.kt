package io.github.hazymorning.klangbild.render

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The outline of the artwork on the wall: a square with softly rounded corners ([corner],
 * dp), or a disc (generated sand plates hang on the wall without a square around them).
 */
data class PrintShape(val rect: RectDp, val round: Boolean = false, val corner: Float = 0f) {
    val centerX get() = rect.centerX
    val centerY get() = rect.centerY

    /** Half the size (dp): for a disc its radius. */
    val radius get() = rect.width / 2f

    /** Distance (dp) of a point from the outline; 0 inside. */
    fun distance(xDp: Float, yDp: Float): Float = max(0f, signedDistance(xDp, yDp))

    /** Distance from the outline, negative inside. */
    fun signedDistance(xDp: Float, yDp: Float): Float =
        if (round) {
            hypot(xDp - centerX, yDp - centerY) - radius
        } else {
            val r = corner.coerceIn(0f, rect.width / 2f)
            val qx = kotlin.math.abs(xDp - centerX) - (rect.width / 2f - r)
            val qy = kotlin.math.abs(yDp - centerY) - (rect.height / 2f - r)
            val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
            outside + kotlin.math.min(max(qx, qy), 0f) - r
        }

    fun contains(xDp: Float, yDp: Float): Boolean = signedDistance(xDp, yDp) < 0f
}
