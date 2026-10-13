package io.github.hazymorning.klangbild.render

import android.util.DisplayMetrics

/** Physical screen the scene is prepared for. */
data class ScreenSpec(val widthPx: Int, val heightPx: Int, val density: Float) {
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density

    companion object {
        val TV_1080 = ScreenSpec(1920, 1080, 2f)

        /** The display of [metrics], always seen in landscape: the longer side is the width. */
        fun of(metrics: DisplayMetrics) =
            ScreenSpec(maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels), metrics.density)
    }
}
