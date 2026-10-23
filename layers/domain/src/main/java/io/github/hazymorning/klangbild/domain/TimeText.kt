package io.github.hazymorning.klangbild.domain

import io.github.hazymorning.klangbild.text.R
import io.github.hazymorning.klangbild.text.Texts
import java.util.Locale

/** Durations as text, and the one separator every line uses. */
object TimeText {
    /** The one separator used everywhere: a middle dot, glued to the word before it. */
    const val SEPARATOR = " · "

    /** "Speed 1.5×" for spoken word that plays faster or slower than it was read; null at its own pace. */
    fun speed(speed: Float, t: Texts): String? {
        val hundredths = kotlin.math.round(speed * 100f).toInt()
        if (hundredths == 100) return null
        return t.format(R.string.status_speed, t.decimal(hundredths / 100.0, 2))
    }

    /** "48 min", "2 h", "1 h 12 min": how long something plays, to the minute (at least one). */
    fun length(ms: Long, t: Texts): String {
        val minutes = ((ms + 30_000L) / 60_000L).coerceAtLeast(1L).toInt()
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> t.format(R.string.queue_minutes, m)
            m == 0 -> t.format(R.string.queue_hours, h)
            else -> t.format(R.string.queue_hours_minutes, h, m)
        }
    }

    /** "0:07", "3:41", "1:02:05": as a clock shows it, no leading zero. */
    fun formatClock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }
}
