package io.github.hazymorning.klangbild.domain

/** A card in Cover Flow: one cover (an album, a station) and the track it shows. */
data class StripCard(val id: Long, val track: TrackInfo) {
    val picture: String get() = track.pictureKey
}

/** Cover Flow's cards in play order: the card at [current] is in the middle. */
data class Strip(val cards: List<StripCard>, val current: Int) {
    val middle: StripCard get() = cards[current]
}

/**
 * Keeps Cover Flow's strip as the music moves on: the covers played before, the one playing, and
 * those to come. The covers are distinct, so the tracks of one album are one card. A card keeps
 * its id from the moment it appears until it leaves the strip, so the UI can animate between two
 * strips card by card: the next cover arriving is the card that was to the right, a step back
 * brings back the card from the left, anything else is a new card. A track whose cover changes
 * while it plays (a station's track getting its own) keeps its card.
 */
class CoverStrip {
    private var lastId = 0L
    private val history = ArrayList<StripCard>()
    private var current: StripCard? = null
    private var upcoming: List<StripCard> = emptyList()

    /**
     * [track] plays now, [coming] is what follows in the queue (null while not known yet: then
     * what stood behind a card that came to the middle stays), [played] what the queue played
     * before it (most recent last; empty when unknown, and then the strip remembers on its own).
     */
    fun update(track: TrackInfo, coming: List<TrackInfo>? = null, played: List<TrackInfo> = emptyList()): Strip {
        val picture = track.pictureKey
        val cur = current
        var spare: StripCard? = null
        var stays: List<StripCard> = emptyList()
        val now = when {
            cur == null -> card(track)
            cur.picture == picture || cur.track.key == track.key -> {
                stays = upcoming
                cur.copy(track = track)
            }
            upcoming.firstOrNull()?.picture == picture -> {
                remember(cur)
                stays = upcoming.drop(1)
                upcoming.first().copy(track = track)
            }
            history.lastOrNull()?.picture == picture -> {
                spare = cur
                stays = listOf(cur) + upcoming
                history.removeAt(history.lastIndex).copy(track = track)
            }
            else -> {
                remember(cur)
                card(track)
            }
        }
        current = now
        if (played.isNotEmpty()) {
            val known = (history + listOfNotNull(spare)).associateBy { it.picture }
            val cards = distinct(played.asReversed(), now.picture).take(KEEP).map { t -> known[t.pictureKey]?.copy(track = t) ?: card(t) }
            history.clear()
            history.addAll(cards.asReversed())
        }
        val reuse = (upcoming + listOfNotNull(spare)).associateBy { it.picture }
        upcoming = if (coming == null) {
            stays.take(SIDE)
        } else {
            distinct(coming, now.picture).take(SIDE).map { t -> reuse[t.pictureKey]?.copy(track = t) ?: card(t) }
        }
        val shown = history.takeLast(SIDE)
        return Strip(shown + now + upcoming, shown.size)
    }

    /** Forgets everything (another speaker, or the queue to or from Cover Flow). */
    fun clear() {
        history.clear()
        current = null
        upcoming = emptyList()
    }

    private fun card(track: TrackInfo) = StripCard(++lastId, track)

    private fun remember(card: StripCard) {
        if (history.lastOrNull()?.picture != card.picture) history += card
        while (history.size > KEEP) history.removeAt(0)
    }

    /** [tracks] with consecutive repeats of a cover merged, the current cover left out. */
    private fun distinct(tracks: List<TrackInfo>, currentPicture: String): List<TrackInfo> {
        val out = ArrayList<TrackInfo>()
        for (t in tracks) {
            val p = t.pictureKey
            if (p == currentPicture && out.isEmpty()) continue
            if (out.lastOrNull()?.pictureKey == p) continue
            out += t
        }
        return out
    }

    companion object {
        /** Cards shown on each side of the middle. */
        const val SIDE = 2

        /** Covers remembered behind the current one. */
        const val KEEP = 6
    }
}
