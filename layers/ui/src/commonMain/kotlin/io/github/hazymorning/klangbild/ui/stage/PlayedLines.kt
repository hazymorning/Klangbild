package io.github.hazymorning.klangbild.ui.stage

import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.math.RecentMap

/**
 * What each track's lines showed while it played, so that a stage still on screen after its track
 * has ended (the next track's scene still being made, or its lines leaving in a change) keeps its
 * own position, its own next track and its own chapter.
 */
internal class PlayedLines {
    private class Played {
        var position: Long? = null
        var next: TrackInfo? = null
        var chapter: ChapterPlace? = null

        /** It played at the last read of its position. */
        var plays = false
    }

    /** The tracks of the last few changes, the one used last at the end. */
    private val tracks = RecentMap<String, Played>(KEPT)

    private fun of(key: String) = tracks.getOrPut(key) { Played() }

    /** The track after [key]: [now] while it [plays] (and kept), else the one it had. */
    fun next(key: String, plays: Boolean, now: TrackInfo?): TrackInfo? {
        val p = of(key)
        if (plays) p.next = now
        return p.next
    }

    /** The chapter [key] stands in: [now] while it [plays] (and kept), else the last one. */
    fun chapter(key: String, plays: Boolean, now: ChapterPlace?): ChapterPlace? {
        val p = of(key)
        if (plays) p.chapter = now
        return p.chapter
    }

    /**
     * Where [key] stands: read [now] while it [plays] and kept, else where it stood last. A read
     * that knows nothing (another track is reported already, or none) keeps the last position,
     * and a track that plays again after another starts afresh.
     */
    fun position(key: String, plays: Boolean, now: () -> Long?): Long? {
        val p = of(key)
        if (plays && !p.plays) p.position = null
        p.plays = plays
        if (plays) now()?.let { p.position = it }
        return p.position
    }

    private companion object {
        /** The track that plays, the one leaving, and room for a quick skip or two. */
        const val KEPT = 4
    }
}
