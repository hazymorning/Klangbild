package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.ui.graphics.Matrix
import kotlin.math.abs

/**
 * The perspective that maps one quad onto another, worked out as Skia's
 * `SkMatrix::setPolyToPoly` does for four points (Android 14's Skia), in the same order and
 * precision, so the cards are drawn through the very matrix Android drew them through. Holds its
 * working matrices, so a frame allocates nothing.
 */
internal class Perspective {
    private val from = FloatArray(9)
    private val inverse = FloatArray(9)
    private val to = FloatArray(9)
    private val result = FloatArray(9)

    /**
     * Sets [matrix] to the perspective that maps the corners [src] onto [dst] (x and y of four
     * corners each, clockwise from the top left). False for a quad without area, which leaves
     * [matrix] as it was.
     */
    fun map(src: FloatArray, dst: FloatArray, matrix: Matrix): Boolean {
        if (!squareToQuad(src, from) || !invert(from, inverse) || !squareToQuad(dst, to)) return false
        // The inverse keeps the kind of the matrix it was made from, as Skia notes it.
        concat(to, kind(to), inverse, kind(from), result)
        matrix.reset()
        val v = matrix.values
        v[0] = result[SCALE_X]
        v[1] = result[SKEW_Y]
        v[3] = result[PERSP_0]
        v[4] = result[SKEW_X]
        v[5] = result[SCALE_Y]
        v[7] = result[PERSP_1]
        v[12] = result[TRANS_X]
        v[13] = result[TRANS_Y]
        v[15] = result[PERSP_2]
        return true
    }

    private companion object {
        // A 3x3 matrix's places, row by row, as Skia keeps them.
        const val SCALE_X = 0
        const val SKEW_X = 1
        const val TRANS_X = 2
        const val SKEW_Y = 3
        const val SCALE_Y = 4
        const val TRANS_Y = 5
        const val PERSP_0 = 6
        const val PERSP_1 = 7
        const val PERSP_2 = 8

        // The kinds of matrix (SkMatrix::TypeMask).
        const val TRANSLATE = 0x01
        const val SCALE = 0x02
        const val AFFINE = 0x04
        const val PERSPECTIVE = 0x08

        /** The matrix that maps the unit square onto the quad [p] (SkMatrix::Poly4Proc). */
        fun squareToQuad(p: FloatArray, m: FloatArray): Boolean {
            val x0 = p[4] - p[0]
            val y0 = p[5] - p[1]
            val x1 = p[4] - p[2]
            val y1 = p[5] - p[3]
            val x2 = p[4] - p[6]
            val y2 = p[5] - p[7]
            val a1: Float
            val a2: Float
            // Whether abs(x2) > abs(y2).
            if (if (x2 > 0) (if (y2 > 0) x2 > y2 else x2 > -y2) else (if (y2 > 0) -x2 > y2 else x2 < y2)) {
                val denom = x1 * y2 / x2 - y1
                if (denom * denom == 0f) return false
                a1 = ((x0 - x1) * y2 / x2 - y0 + y1) / denom
            } else {
                val denom = x1 - y1 * x2 / y2
                if (denom * denom == 0f) return false
                a1 = (x0 - x1 - (y0 - y1) * x2 / y2) / denom
            }
            // Whether abs(x1) > abs(y1).
            if (if (x1 > 0) (if (y1 > 0) x1 > y1 else x1 > -y1) else (if (y1 > 0) -x1 > y1 else x1 < y1)) {
                val denom = y2 - x2 * y1 / x1
                if (denom * denom == 0f) return false
                a2 = (y0 - y2 - (x0 - x2) * y1 / x1) / denom
            } else {
                val denom = y2 * x1 / y1 - x2
                if (denom * denom == 0f) return false
                a2 = ((y0 - y2) * x1 / y1 - x0 + x2) / denom
            }
            m[SCALE_X] = a2 * p[6] + p[6] - p[0]
            m[SKEW_Y] = a2 * p[7] + p[7] - p[1]
            m[PERSP_0] = a2
            m[SKEW_X] = a1 * p[2] + p[2] - p[0]
            m[SCALE_Y] = a1 * p[3] + p[3] - p[1]
            m[PERSP_1] = a1
            m[TRANS_X] = p[0]
            m[TRANS_Y] = p[1]
            m[PERSP_2] = 1f
            return true
        }

        /** What kind of matrix [m] is (SkMatrix::computeTypeMask, without its private bits). */
        fun kind(m: FloatArray): Int {
            if (m[PERSP_0] != 0f || m[PERSP_1] != 0f || m[PERSP_2] != 1f) return TRANSLATE or SCALE or AFFINE or PERSPECTIVE
            var kind = if (m[TRANS_X] != 0f || m[TRANS_Y] != 0f) TRANSLATE else 0
            if (m[SKEW_X] != 0f || m[SKEW_Y] != 0f) {
                kind = kind or AFFINE or SCALE
            } else if (m[SCALE_X] != 1f || m[SCALE_Y] != 1f) {
                kind = kind or SCALE
            }
            return kind
        }

        /** [m]'s inverse in [out] (SkMatrix::invert), false if it has none. */
        fun invert(m: FloatArray, out: FloatArray): Boolean {
            val kind = kind(m)
            if (kind == 0) {
                identity(out)
                return true
            }
            if (kind and (SCALE or TRANSLATE).inv() == 0) {
                if (kind and SCALE != 0) {
                    if (m[SCALE_X] == 0f || m[SCALE_Y] == 0f) return false
                    val invX = 1f / m[SCALE_X]
                    val invY = 1f / m[SCALE_Y]
                    identity(out)
                    out[SCALE_X] = invX
                    out[SCALE_Y] = invY
                    out[TRANS_X] = -m[TRANS_X] * invX
                    out[TRANS_Y] = -m[TRANS_Y] * invY
                } else {
                    identity(out)
                    out[TRANS_X] = -m[TRANS_X]
                    out[TRANS_Y] = -m[TRANS_Y]
                }
                return true
            }
            val perspective = kind and PERSPECTIVE != 0
            val det = if (perspective) {
                m[SCALE_X] * cross(m[SCALE_Y], m[PERSP_2], m[TRANS_Y], m[PERSP_1]) +
                    m[SKEW_X] * cross(m[TRANS_Y], m[PERSP_0], m[SKEW_Y], m[PERSP_2]) +
                    m[TRANS_X] * cross(m[SKEW_Y], m[PERSP_1], m[SCALE_Y], m[PERSP_0])
            } else {
                cross(m[SCALE_X], m[SCALE_Y], m[SKEW_X], m[SKEW_Y])
            }
            if (abs(det.toFloat()) <= NEARLY_ZERO * NEARLY_ZERO * NEARLY_ZERO) return false
            val invDet = 1.0 / det
            if (perspective) {
                out[SCALE_X] = scaled(m[SCALE_Y], m[PERSP_2], m[TRANS_Y], m[PERSP_1], invDet)
                out[SKEW_X] = scaled(m[TRANS_X], m[PERSP_1], m[SKEW_X], m[PERSP_2], invDet)
                out[TRANS_X] = scaled(m[SKEW_X], m[TRANS_Y], m[TRANS_X], m[SCALE_Y], invDet)
                out[SKEW_Y] = scaled(m[TRANS_Y], m[PERSP_0], m[SKEW_Y], m[PERSP_2], invDet)
                out[SCALE_Y] = scaled(m[SCALE_X], m[PERSP_2], m[TRANS_X], m[PERSP_0], invDet)
                out[TRANS_Y] = scaled(m[TRANS_X], m[SKEW_Y], m[SCALE_X], m[TRANS_Y], invDet)
                out[PERSP_0] = scaled(m[SKEW_Y], m[PERSP_1], m[SCALE_Y], m[PERSP_0], invDet)
                out[PERSP_1] = scaled(m[SKEW_X], m[PERSP_0], m[SCALE_X], m[PERSP_1], invDet)
                out[PERSP_2] = scaled(m[SCALE_X], m[SCALE_Y], m[SKEW_X], m[SKEW_Y], invDet)
            } else {
                out[SCALE_X] = (m[SCALE_Y] * invDet).toFloat()
                out[SKEW_X] = (-m[SKEW_X] * invDet).toFloat()
                out[TRANS_X] = (cross(m[SKEW_X], m[TRANS_Y], m[SCALE_Y], m[TRANS_X]) * invDet).toFloat()
                out[SKEW_Y] = (-m[SKEW_Y] * invDet).toFloat()
                out[SCALE_Y] = (m[SCALE_X] * invDet).toFloat()
                out[TRANS_Y] = (cross(m[SKEW_Y], m[TRANS_X], m[SCALE_X], m[TRANS_Y]) * invDet).toFloat()
                out[PERSP_0] = 0f
                out[PERSP_1] = 0f
                out[PERSP_2] = 1f
            }
            return out.all { it.isFinite() }
        }

        /** [a] after [b], that is a × b, in [out] (SkMatrix::setConcat), given their kinds. */
        fun concat(a: FloatArray, aKind: Int, b: FloatArray, bKind: Int, out: FloatArray) {
            when {
                aKind == 0 -> b.copyInto(out)
                bKind == 0 -> a.copyInto(out)
                (aKind or bKind) and (AFFINE or PERSPECTIVE) == 0 -> {
                    identity(out)
                    out[SCALE_X] = a[SCALE_X] * b[SCALE_X]
                    out[SCALE_Y] = a[SCALE_Y] * b[SCALE_Y]
                    out[TRANS_X] = a[SCALE_X] * b[TRANS_X] + a[TRANS_X]
                    out[TRANS_Y] = a[SCALE_Y] * b[TRANS_Y] + a[TRANS_Y]
                }
                (aKind or bKind) and PERSPECTIVE != 0 -> {
                    for (row in 0 until 3) for (column in 0 until 3) {
                        out[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[column + 3] + a[row * 3 + 2] * b[column + 6]
                    }
                }
                else -> {
                    out[SCALE_X] = sum(a[SCALE_X], b[SCALE_X], a[SKEW_X], b[SKEW_Y])
                    out[SKEW_X] = sum(a[SCALE_X], b[SKEW_X], a[SKEW_X], b[SCALE_Y])
                    out[TRANS_X] = sum(a[SCALE_X], b[TRANS_X], a[SKEW_X], b[TRANS_Y]) + a[TRANS_X]
                    out[SKEW_Y] = sum(a[SKEW_Y], b[SCALE_X], a[SCALE_Y], b[SKEW_Y])
                    out[SCALE_Y] = sum(a[SKEW_Y], b[SKEW_X], a[SCALE_Y], b[SCALE_Y])
                    out[TRANS_Y] = sum(a[SKEW_Y], b[TRANS_X], a[SCALE_Y], b[TRANS_Y]) + a[TRANS_Y]
                    out[PERSP_0] = 0f
                    out[PERSP_1] = 0f
                    out[PERSP_2] = 1f
                }
            }
        }

        fun identity(m: FloatArray) {
            m.fill(0f)
            m[SCALE_X] = 1f
            m[SCALE_Y] = 1f
            m[PERSP_2] = 1f
        }

        /** a·b − c·d in double precision (dcross). */
        fun cross(a: Float, b: Float, c: Float, d: Float): Double = a.toDouble() * b - c.toDouble() * d

        /** a·b − c·d in single precision, scaled in double (scross_dscale). */
        fun scaled(a: Float, b: Float, c: Float, d: Float, scale: Double): Float = ((a * b - c * d) * scale).toFloat()

        /** a·b + c·d in double precision, rounded to single (muladdmul). */
        fun sum(a: Float, b: Float, c: Float, d: Float): Float = (a.toDouble() * b + c.toDouble() * d).toFloat()

        /** SK_ScalarNearlyZero. */
        const val NEARLY_ZERO = 1f / (1 shl 12)
    }
}
