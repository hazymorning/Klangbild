package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.domain.pictureId
import io.github.hazymorning.klangbild.math.RecentMap

/**
 * Which picture a station's song shows. Home Assistant shows Music Assistant's choice of the
 * moment: the song's own picture once Music Assistant has one, else the station's. That choice
 * comes in steps (the station's picture first, the song's once Music Assistant has looked it
 * up) and can fall back for a while, so here the pictures are ranked:
 *
 * 1. [Rank.SONG]: the song's own picture, as Music Assistant's queue names it, or one with an
 *    address of its own (a provider's art; Home Assistant serves the station's picture through
 *    its own proxy);
 * 2. [Rank.UNKNOWN]: any other picture;
 * 3. [Rank.STATION]: the station's picture, as the queue gives it, or any picture already seen
 *    with another song.
 *
 * While a song plays, its picture never steps down a rank and never returns to one it has
 * left; a better picture, or a new one of the same rank, takes its place. The next song starts
 * afresh, with what was learned about the station's pictures.
 */
internal class StationPictures {
    enum class Rank { STATION, UNKNOWN, SONG }

    /**
     * A picture on offer: what to load (and what to try if that fails), its rank, and whether
     * the rank is [certain] (said by a queue) or only presumed from the address.
     */
    data class Offer(val url: String, val fallback: String?, val rank: Rank, val certain: Boolean = false)

    private var song: String? = null
    private var chosen: Offer? = null

    /** Pictures the song has moved on from, by identity. */
    private val left = HashSet<String>()

    /** The song each picture was first seen with, by identity: the most recent few. */
    private val firstSeen = RecentMap<String, String>(MEMORY)

    /** Pictures known to be the station's, by identity: the most recent few. */
    private val stations = RecentMap<String, Unit>(MEMORY)

    /** The picture for [song] (its identity) from [offers], best first among equals; null when there is none at all. */
    fun choose(song: String, offers: List<Offer>): Offer? {
        if (song != this.song) {
            this.song = song
            chosen = null
            left.clear()
        }
        for (o in offers) {
            val id = pictureId(o.url)
            if (o.certain && o.rank == Rank.STATION) stations[id] = Unit
            // A picture that came with another song as well is the station's.
            if (firstSeen.getOrPut(id) { song } != song) stations[id] = Unit
        }
        val best = offers.maxByOrNull { rankOf(it).ordinal } ?: return chosen
        val current = chosen
        val bestId = pictureId(best.url)
        when {
            current == null -> chosen = best
            // The same picture (a renewed address): it stays, at the rank it was taken with.
            pictureId(current.url) == bestId -> chosen = current.copy(url = best.url, fallback = best.fallback ?: current.fallback)
            rankOf(best) > rankOf(current) || (rankOf(best) == rankOf(current) && bestId !in left) -> {
                left += pictureId(current.url)
                chosen = best
            }
        }
        return chosen
    }

    /** [o]'s rank as far as known now: a presumed one gives way to what was learned about the station. */
    private fun rankOf(o: Offer): Rank = if (!o.certain && pictureId(o.url) in stations) Rank.STATION else o.rank

    private companion object {
        /** Pictures remembered: far more than a station shows in an evening's few songs. */
        const val MEMORY = 64
    }
}
