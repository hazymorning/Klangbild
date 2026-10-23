package io.github.hazymorning.klangbild.data.ha

import doist.x.normalize.Form
import doist.x.normalize.normalize
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.TrackInfo

/**
 * Whether Home Assistant's player and a queue describe the same song: they spell it a little
 * differently (accents, a version in brackets), so titles are matched loosely.
 */
object TrackMatch {
    /** One song, however its title and artists are spelled. */
    fun key(t: TrackInfo) = norm(t.title) + "|" + norm(t.artists.joinToString())

    fun norm(s: String): String =
        s.lowercase().normalize(Form.NFKD).filter { it.isLetterOrDigit() }

    /** Two descriptions of one station (the song each names may differ by a moment). */
    fun sameStation(a: TrackInfo, b: TrackInfo): Boolean =
        a.kind == MediaKind.RADIO && b.kind == MediaKind.RADIO && a.source != null && a.source == b.source

    fun sameTitle(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x == y || (x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x)))
    }
}
