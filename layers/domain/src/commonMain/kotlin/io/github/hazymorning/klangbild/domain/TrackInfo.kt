package io.github.hazymorning.klangbild.domain

/**
 * What is playing, normalized from Home Assistant / Music Assistant.
 * Only plain data; presentation decisions live in [TrackText].
 */
data class TrackInfo(
    val title: String,
    /** Individual artist names when known (MA queue), otherwise one combined string. */
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    /** Best artwork URL, absolute. */
    val artworkUrl: String? = null,
    /** Alternative artwork URL to try if [artworkUrl] fails (e.g. the HA proxy). */
    val artworkFallbackUrl: String? = null,
    /** Radio station or other source label, shown when it adds information. */
    val source: String? = null,
    /** Music, a station, a podcast episode or an audiobook. */
    val kind: MediaKind = MediaKind.MUSIC,
    /** The chapters of a book or an episode (from Music Assistant), sorted; empty without any. */
    val chapters: List<Chapter> = emptyList(),
    /** Music Assistant has it among the favorites (only its own queue says so). */
    val favorite: Boolean = false,
    /** A station's song with its own picture (from the stream). It goes before any other. */
    val songPicture: Boolean = false,
    /** Which item of Music Assistant's queue this is: the same song twice in the queue is two items. */
    val queueItemId: String? = null,
    /** Stable identity used for transitions and caching. */
    val key: String = listOf(title, artists.joinToString(), album.orEmpty()).joinToString("|"),
) {
    /**
     * What generated artwork follows for a track without a cover: its foil the first artist (for
     * a station its name), its view the album or the title. Neither for what has only a title,
     * like a TV's sound, which gets a plain one.
     */
    val artFoil: String? get() = if (kind == MediaKind.RADIO) source ?: title else artists.firstOrNull() ?: album ?: source
    val artView: String? get() = if (kind == MediaKind.RADIO || artFoil == null) null else album ?: title

    /** Which generated artwork this is: the same for every track that would get the same one. */
    val artKey: String get() = "${artFoil.orEmpty()}|${artView.orEmpty()}"

    /**
     * Which picture this is: its address without the access token in Home Assistant's own
     * picture links, which changes every few minutes while the picture stays the same.
     */
    val artworkId: String? get() = artworkUrl?.let(::pictureId)

    /**
     * Identity of the picture this track shows: the album (one card for all its songs, even
     * where each song carries its own copy of the artwork), else the artwork, else the
     * generated artwork's.
     */
    val pictureKey: String
        get() = album?.takeIf { it.isNotBlank() }?.let { "album|" + it.trim().lowercase() }
            ?: artworkId?.let { "art|$it" }
            ?: "seed|$artKey"

    /**
     * What a scene is made for: the song, and for a station also its picture. A station's song
     * often shows the station's picture first and its own a moment later; the new picture then
     * gets a scene of its own and takes the old one's place.
     */
    val sceneKey: String get() = if (kind == MediaKind.RADIO) key + "|" + artworkId.orEmpty() else key
}

/**
 * Which picture [url] is: the address without its `token` query parameter, which Home
 * Assistant renews every few minutes in the links to its own pictures.
 */
fun pictureId(url: String): String {
    val query = url.indexOf('?')
    if (query < 0 || !url.contains("token=")) return url
    val end = url.indexOf('#', query).let { if (it < 0) url.length else it }
    val kept = url.substring(query + 1, end).split('&').filter { it.isNotEmpty() && !it.startsWith("token=") }
    return url.substring(0, query) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(end)
}
