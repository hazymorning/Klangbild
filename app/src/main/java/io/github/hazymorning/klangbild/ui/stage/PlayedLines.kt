package io.github.hazymorning.klangbild.ui.stage

import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.TrackInfo

/**
 * What each song's lines showed while it played, so that a stage still on screen after its song
 * has ended (the next song's scene still being made, or its lines leaving in the change) keeps
 * its own time, its own next and its own chapter. Until 2026.10 a stage left waiting showed the
 * new song's, and lines leaving showed the new song's next and lost their own time and chapter.
 */
internal class PlayedLines {
    private class Played {
        var position: Long? = null
        var next: TrackInfo? = null
        var chapter: ChapterPlace? = null
    }

    /** The songs of the last few changes, the one used last at the end. */
    private val songs = object : LinkedHashMap<String, Played>(KEPT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Played>?) = size > KEPT
    }

    private fun of(key: String) = songs.getOrPut(key) { Played() }

    /** The song after [key]: [now] while it [plays] (and kept), else the one it had. */
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
     * that knows nothing (another song is reported already, or none) keeps the last place.
     */
    fun position(key: String, plays: Boolean, now: () -> Long?): Long? {
        val p = of(key)
        if (plays) now()?.let { p.position = it }
        return p.position
    }

    private companion object {
        /** The song that plays, the one leaving, and room for a quick skip or two. */
        const val KEPT = 4
    }
}
