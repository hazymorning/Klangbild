package io.github.hazymorning.klangbild.ui.stage

import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.TrackInfo

/**
 * What each song's lines showed while it played, so that a stage still on screen after its song
 * has ended (the next song's scene still being made, or its lines leaving in the change) keeps
 * its own time, its own next and its own chapter. Until 2026.10 it took the new song's at once.
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

    /** The song after [key]: [live] while it plays (and kept), else the one it had. */
    fun next(key: String, playing: Boolean, live: TrackInfo?): TrackInfo? {
        val p = of(key)
        if (playing) p.next = live
        return p.next
    }

    /** The chapter [key] stands in: [live] while it plays (and kept), else the last one. */
    fun chapter(key: String, playing: Boolean, live: ChapterPlace?): ChapterPlace? {
        val p = of(key)
        if (playing) p.chapter = live
        return p.chapter
    }

    /** Where [key] stands: [live] while it plays (and kept), else where it stood last. */
    fun position(key: String, playing: Boolean, live: () -> Long?): Long? {
        val p = of(key)
        if (playing) p.position = live()
        return p.position
    }

    private companion object {
        /** The song that plays, the one leaving, and room for a quick skip or two. */
        const val KEPT = 4
    }
}
