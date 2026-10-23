package io.github.hazymorning.klangbild.render

import android.util.DisplayMetrics

/** The display of [metrics], always seen in landscape: the longer side is the width. */
fun ScreenSpec.Companion.of(metrics: DisplayMetrics) =
    ScreenSpec(maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels), metrics.density)
