package io.github.hazymorning.klangbild.domain

/**
 * A track as Home Assistant and Music Assistant report it, normalized, with the keys derived
 * from it. [TrackText] prepares it for display.
 */
data class TrackInfo(
    val title: String,
    /** Individual artist names when known (Music Assistant's queue), otherwise one combined string. */
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    /** Best artwork URL, absolute. */
    val artworkUrl: String? = null,
    /** Alternative artwork URL to try if [artworkUrl] fails (Home Assistant's proxy). */
    val artworkFallbackUrl: String? = null,
    /** Radio station or other source label, shown when it adds information. */
    val source: String? = null,
    /** Music, a station, a podcast episode or an audiobook. */
    val kind: MediaKind = MediaKind.MUSIC,
    /** The chapters of a book or an episode (from Music Assistant), sorted; empty without any. */
    val chapters: List<Chapter> = emptyList(),
    /** Music Assistant has it among the favorites (only its own queue says so). */
    val favorite: Boolean = false,
    /** A station's track with its own artwork (from the stream), which goes before any other. */
    val songPicture: Boolean = false,
    /** Which of Music Assistant's queue items this is: the same track twice in the queue is two queue items. */
    val queueItemId: String? = null,
    /**
     * Identity for transitions and caches: title, artists and album as first reported. A copy
     * that only adds details, like an album or artwork, keeps it.
     */
    val key: String = listOf(title, artists.joinToString(), album.orEmpty()).joinToString("|"),
) {
    /**
     * The names a generated cover follows for a track without artwork: [artFoil] picks its foil
     * (the first artist, else the album, else the source; for a station, its name), [artView]
     * its view (the album, else the title; none for a station). A track with only a title, like
     * a TV's sound, gets neither and so the plain generated cover.
     */
    val artFoil: String? get() = if (kind == MediaKind.RADIO) source ?: title else artists.firstOrNull() ?: album ?: source
    val artView: String? get() = if (kind == MediaKind.RADIO || artFoil == null) null else album ?: title

    /** Which generated cover this is: the same for every track that would get the same one. */
    val artKey: String get() = "${artFoil.orEmpty()}|${artView.orEmpty()}"

    /**
     * Which artwork this is: its URL without the access token in the URLs of Home Assistant's
     * own artwork, which changes every few minutes while the artwork stays the same.
     */
    val artworkId: String? get() = artworkUrl?.let(::pictureId)

    /**
     * Identity of the cover this track shows: the album (one card for all its tracks, even
     * where each track carries its own copy of the artwork), else the artwork, else the
     * generated cover's.
     */
    val pictureKey: String
        get() = album?.takeIf { it.isNotBlank() }?.let { "album|" + it.trim().lowercase() }
            ?: artworkId?.let { "art|$it" }
            ?: "seed|$artKey"

    /**
     * What a scene is made for: the track, and for a station also its artwork. A station's track
     * often shows the station's artwork first and its own a moment later; the new artwork then
     * gets a scene of its own and takes the old one's place.
     */
    val sceneKey: String get() = if (kind == MediaKind.RADIO) key + "|" + artworkId.orEmpty() else key
}

/**
 * Which artwork [url] is: the URL without its `token` query parameter, which Home Assistant
 * renews every few minutes in the URLs of its own artwork.
 */
fun pictureId(url: String): String {
    val query = url.indexOf('?')
    if (query < 0 || !url.contains("token=")) return url
    val end = url.indexOf('#', query).let { if (it < 0) url.length else it }
    val kept = url.substring(query + 1, end).split('&').filter { it.isNotEmpty() && !it.startsWith("token=") }
    return url.substring(0, query) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(end)
}
