package io.github.hazymorning.klangbild.domain

/**
 * What comes next, as the queue's list shows it beside the cover: a row for each song (each one
 * alike: its small cover, title, artists and length), how many follow and how long they play.
 */
data class UpNext(
    val rows: List<UpNextRow>,
    /** How many items follow the song that plays, as far as known. */
    val count: Int?,
    /** How long they play together (ms), when every one of them is known and has a length. */
    val durationMs: Long?,
) {
    companion object {
        /** Rows kept: more than the column ever shows, so a row that moves into the column is there. */
        const val ROWS = 8

        /**
         * The list for the songs that follow the one that plays ([coming], from Music Assistant's
         * queue), with [remaining] items after it in all. Null when nothing follows.
         */
        fun of(coming: List<TrackInfo>, remaining: Int?): UpNext? {
            if (coming.isEmpty()) return null
            val seen = HashMap<String, Int>()
            val rows = coming.take(ROWS).map { track ->
                // Without Music Assistant's id: the song, and how often it came before in the list.
                UpNextRow(track.queueItemId ?: (track.key + "#" + seen.merge(track.key, 1, Int::plus)), track)
            }
            // The queue may be longer than what was read of it: then only its count is known.
            val count = remaining?.coerceAtLeast(coming.size) ?: coming.size.takeIf { it < READ_LIMIT }
            val all = count != null && count == coming.size
            val durationMs = if (all && coming.all { it.durationMs != null }) coming.sumOf { it.durationMs!! } else null
            return UpNext(rows, count, durationMs)
        }

        /** As many items as Music Assistant's queue is read ahead (`MaQueue.REACH`): a list this long may go on. */
        private const val READ_LIMIT = 40
    }
}

/** One song of the list: [id] stays the same while it moves up the queue. */
data class UpNextRow(val id: String, val track: TrackInfo)
