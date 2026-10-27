package io.github.hazymorning.klangbild.color

/**
 * The warm lamp every color on screen is seen in: it shifts colors a little toward amber in
 * proportion to their lightness, so black stays black. It is the same at every hour; only the
 * background and the lights behind the TV follow the sun.
 */
object Lamp {
    /** How warm the lamp is (0..1). */
    const val WARMTH = 0.06f

    /** The lamp's own hue (Oklab, degrees): a warm amber. Neutral covers and the screens without one take it. */
    const val HUE = 72f

    /**
     * [lab] as the lamp shows it: its a and b lose a little chroma and move toward amber
     * (0.012, 0.050) in proportion to its lightness.
     */
    fun apply(lab: Lab): Lab {
        val w = WARMTH * lab.l
        return Lab(
            lab.l,
            lab.a * (1f - 0.12f * WARMTH) + 0.012f * w,
            lab.b * (1f - 0.18f * WARMTH) + 0.050f * w,
        )
    }

    private fun applyArgb(argb: Int): Int = apply(Oklab.fromArgb(argb)).toArgb(argb ushr 24)

    /** A near-neutral color of lightness [l] with [chroma] in the lamp's hue, as the lamp shows it. */
    fun neutral(l: Float, chroma: Float = 0.004f): Int = applyArgb(Lch(l, chroma, HUE).toArgb())
}
