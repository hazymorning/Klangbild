package io.github.hazymorning.klangbild.domain

/**
 * What comes next, as the queue's list shows it beside the cover: a row for each song, each one
 * alike (its small cover, title, artists and length).
 */
data class UpNext(val rows: List<UpNextRow>) {
    companion object {
        /** Rows kept: more than the column ever shows, so a row that moves into the column is there. */
        const val ROWS = 8

        /** The list for the songs that follow the one that plays ([coming], from Music Assistant's queue). Null when nothing follows. */
        fun of(coming: List<TrackInfo>): UpNext? {
            if (coming.isEmpty()) return null
            val seen = HashMap<String, Int>()
            val rows = coming.take(ROWS).map { track ->
                // Without Music Assistant's id: the song, and how often it came before in the list.
                UpNextRow(track.queueItemId ?: (track.key + "#" + ((seen[track.key] ?: 0) + 1).also { seen[track.key] = it }), track)
            }
            return UpNext(rows)
        }
    }
}

/** One song of the list: [id] stays the same while it moves up the queue. */
data class UpNextRow(val id: String, val track: TrackInfo)
