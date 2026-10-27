package io.github.hazymorning.klangbild.domain

import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts

/** Durations and playback speed as text, and the one separator every line uses. */
object TimeText {
    /** The one separator used everywhere: a middle dot, glued to the word before it. */
    const val SEPARATOR = " · "

    /** "Speed 1.5×" for spoken word that plays faster or slower than it was read; null at its own pace. */
    fun speed(speed: Float, t: Texts): String? {
        val hundredths = kotlin.math.round(speed * 100f).toInt()
        if (hundredths == 100) return null
        return t.format(Strings.statusSpeed, t.decimal(hundredths / 100.0, 2))
    }

    /** "0:07", "3:41", "1:02:05": as a clock shows it, no leading zero. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    private fun two(n: Long) = n.toString().padStart(2, '0')
}
