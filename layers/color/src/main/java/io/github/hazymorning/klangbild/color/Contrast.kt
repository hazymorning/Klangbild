package io.github.hazymorning.klangbild.color

/** WCAG relative luminance of an sRGB color, 0..1. */
fun relativeLuminance(argb: Int): Float =
    0.2126f * Oklab.srgbToLinear(argb shr 16) + 0.7152f * Oklab.srgbToLinear(argb shr 8) + 0.0722f * Oklab.srgbToLinear(argb)

/** WCAG contrast ratio between two opaque colors (1..21). */
fun contrastRatio(a: Int, b: Int): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
