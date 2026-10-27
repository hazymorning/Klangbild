package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.data.formEncode
import io.github.hazymorning.klangbild.domain.Chapter
import io.github.hazymorning.klangbild.domain.Chapters
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToLong

/**
 * The part of a queue around its current item: what was played (most recent last), what
 * comes, and how fast the current item plays (a book at 1.5×). The current item and the next,
 * when they are songs, as far as their words go ([currentSong], [nextSong]).
 */
data class QueueWindow(
    val played: List<TrackInfo>,
    val current: TrackInfo?,
    val coming: List<TrackInfo>,
    val speed: Float = 1f,
    val currentSong: QueueSong? = null,
    val nextSong: QueueSong? = null,
)

/**
 * A song of Music Assistant's queue as far as its words go: its URI, the item as Music Assistant
 * gave it (to ask for its words with), and the words it brought along ([lyrics] plain or with
 * times, [lrcLyrics] with times), if any.
 */
data class QueueSong(val uri: String, val item: JsonObject, val lyrics: String?, val lrcLyrics: String?)

/**
 * Music Assistant's queue as it stands now (`player_queues/get`): the current item's place, the
 * current item, the next one (null when nothing follows) and its id, and how many items it has.
 */
data class QueueNow(val index: Int?, val current: TrackInfo?, val next: TrackInfo?, val nextId: String?, val items: Int?)

/**
 * Where Music Assistant's queue stands in its current item: [elapsedMs] in the item's own time at
 * [atMs] on Music Assistant's clock (its wall clock), moving on while [playing].
 */
data class QueuePosition(val elapsedMs: Long, val atMs: Long, val playing: Boolean)

/** Reads Music Assistant's queue items (`player_queues/items`). */
object MaQueue {
    /**
     * How many items before and after the current one are read: enough to find the next two
     * albums and the last few behind, even when every album has twenty songs.
     */
    const val REACH = 40

    /** The offset and limit that read [REACH] items around [currentIndex]. */
    fun range(currentIndex: Int): Pair<Int, Int> {
        val offset = (currentIndex - REACH).coerceAtLeast(0)
        return offset to (currentIndex - offset) + 1 + REACH
    }

    /** The window around [currentIndex] from [items] read from [offset]; [baseUrl] resolves pictures. */
    fun window(items: JsonArray, offset: Int, currentIndex: Int, baseUrl: String): QueueWindow {
        val tracks = items.map { (it as? JsonObject)?.let { o -> track(o, baseUrl) } }
        val at = currentIndex - offset
        return QueueWindow(
            played = tracks.take(at.coerceIn(0, tracks.size)).filterNotNull(),
            current = tracks.getOrNull(at),
            coming = if (at < 0) emptyList() else tracks.drop(at + 1).filterNotNull(),
            speed = (items.getOrNull(at) as? JsonObject)?.let(::speed) ?: 1f,
            currentSong = (items.getOrNull(at) as? JsonObject)?.takeIf { at >= 0 }?.let(::song),
            nextSong = (items.getOrNull(at + 1) as? JsonObject)?.takeIf { at >= 0 }?.let(::song),
        )
    }

    /** What [queue] (`player_queues/get`) says now; [baseUrl] resolves pictures. */
    fun now(queue: JsonObject, baseUrl: String): QueueNow {
        val current = queue["current_item"] as? JsonObject
        val next = queue["next_item"] as? JsonObject
        return QueueNow(
            index = (queue["current_index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 },
            current = current?.let { track(it, baseUrl) },
            next = next?.let { track(it, baseUrl) },
            nextId = next?.string("queue_item_id"),
            items = (queue["items"] as? JsonPrimitive)?.intOrNull,
        )
    }

    /**
     * Where [queue] (`player_queues/get`) stands in its current item: its elapsed time, in its
     * own time to the fraction of a second, as it was at a moment of Music Assistant's clock;
     * null when it does not say.
     */
    fun position(queue: JsonObject): QueuePosition? {
        val elapsed = (queue["elapsed_time"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val updated = (queue["elapsed_time_last_updated"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return QueuePosition(
            elapsedMs = (elapsed * 1_000).roundToLong(),
            atMs = (updated * 1_000).roundToLong(),
            playing = queue.string("state") == "playing",
        )
    }

    /** A queue item that is a song, with its URI and the words it brought (`media_item.metadata`); null for anything else. */
    fun song(item: JsonObject): QueueSong? {
        val media = item["media_item"] as? JsonObject ?: return null
        if (media.string("media_type") != "track") return null
        val uri = media.string("uri")?.takeIf { it.isNotBlank() } ?: return null
        val metadata = media["metadata"] as? JsonObject
        return QueueSong(uri, media, metadata?.string("lyrics")?.takeIf { it.isNotBlank() }, metadata?.string("lrc_lyrics")?.takeIf { it.isNotBlank() })
    }

    /** How fast an item plays: Music Assistant keeps a book's or an episode's speed with its queue item. */
    fun speed(item: JsonObject): Float =
        ((item["extra_attributes"] as? JsonObject)?.get("playback_speed") as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it in MIN_SPEED..MAX_SPEED }?.toFloat() ?: 1f

    /**
     * One queue item as a track: title (with its version), artists, album and year, picture. A
     * book names its authors and an episode its show where a song names its artists; both bring
     * their chapters. A station's item is the song it plays now, as far as the stream says
     * (title, artist, album and the song's own picture). The station is its source, and the
     * station's picture only comes behind the song's.
     */
    fun track(item: JsonObject, baseUrl: String): TrackInfo? {
        val media = item["media_item"] as? JsonObject
        val name = media?.string("name") ?: item.string("name") ?: return null
        val version = media?.string("version")?.takeIf { it.isNotBlank() }
        val album = media?.get("album") as? JsonObject
        val kind = MediaKind.fromType(media?.string("media_type")) ?: MediaKind.MUSIC
        val isRadio = kind == MediaKind.RADIO
        val stream = item["streamdetails"] as? JsonObject
        val streamTitle = stream?.string("stream_title")?.takeIf { it.isNotBlank() }
        val playing = (stream?.get("stream_metadata") as? JsonObject).takeIf { isRadio }
        val picture = image(item["image"] as? JsonObject, baseUrl)
            ?: image(media?.get("image") as? JsonObject, baseUrl)
            ?: image(album?.get("image") as? JsonObject, baseUrl)
        // The song's own picture, as the stream gives it: before the station's, always. A plain
        // http address outside the home goes through Music Assistant's proxy (Klangbild fetches
        // cleartext only inside the home network).
        val songPicture = playing?.string("image_url")?.let { url ->
            when {
                url.startsWith("https://") || url.startsWith("$baseUrl/") -> url
                url.startsWith("http://") -> "$baseUrl/imageproxy?path=${formEncode(url)}&provider=builtin&size=$PROXY_SIZE"
                else -> null
            }
        }
        val durationMs = ((playing?.get("duration") ?: item["duration"]) as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { (it * 1000).roundToLong() }
        val artists = when (kind) {
            MediaKind.AUDIOBOOK -> names(media?.get("authors"))
            MediaKind.PODCAST -> listOfNotNull((media?.get("podcast") as? JsonObject)?.string("name")?.trim()?.takeIf { it.isNotEmpty() })
            MediaKind.RADIO -> listOfNotNull(playing?.string("artist")?.trim()?.takeIf { it.isNotEmpty() })
            else -> names(media?.get("artists"))
        }
        return TrackInfo(
            title = when {
                isRadio -> playing?.string("title")?.takeIf { it.isNotBlank() } ?: streamTitle ?: name
                version != null -> "$name ($version)"
                else -> name
            },
            artists = artists,
            album = if (isRadio) playing?.string("album")?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) } else album?.string("name")?.takeIf { it.isNotBlank() },
            year = (album?.get("year") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 && !isRadio },
            durationMs = durationMs,
            artworkUrl = songPicture ?: picture?.first,
            artworkFallbackUrl = if (songPicture != null) picture?.first else picture?.second,
            source = if (isRadio) name else null,
            kind = kind,
            chapters = if (kind.spoken) chapters(media, durationMs) else emptyList(),
            favorite = !isRadio && (media?.get("favorite") as? JsonPrimitive)?.booleanOrNull == true,
            songPicture = songPicture != null,
            queueItemId = item.string("queue_item_id")?.takeIf { it.isNotBlank() },
        )
    }

    /** Names from a list of artists or authors: objects with a name, or plain strings (a book's authors may be either). */
    private fun names(list: JsonElement?): List<String> =
        (list as? JsonArray)?.mapNotNull { e ->
            when (e) {
                is JsonObject -> e.string("name")
                is JsonPrimitive -> e.takeIf { it.isString }?.content
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct().orEmpty()

    /** A book's or an episode's chapters (`metadata.chapters`: number, name, start and end in seconds). */
    private fun chapters(media: JsonObject?, durationMs: Long?): List<Chapter> {
        val list = ((media?.get("metadata") as? JsonObject)?.get("chapters") as? JsonArray) ?: return emptyList()
        val raw = list.take(MAX_CHAPTERS).mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val start = (c["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            Chapters.Raw(
                position = (c["position"] as? JsonPrimitive)?.intOrNull ?: 0,
                name = c.string("name"),
                start = start,
                end = (c["end"] as? JsonPrimitive)?.doubleOrNull,
            )
        }
        return Chapters.of(raw, durationMs)
    }

    /**
     * A picture's address and its fallback: a public one (a streaming service's CDN) as it is,
     * with Music Assistant's image proxy behind it; anything else through the proxy only.
     */
    private fun image(image: JsonObject?, baseUrl: String): Pair<String, String>? {
        val path = image?.string("path")?.takeIf { it.isNotBlank() } ?: return null
        val proxy = image.string("proxy_id")?.takeIf { it.isNotBlank() }?.let { "$baseUrl/imageproxy/$it?size=$PROXY_SIZE" }
        val remote = (image["remotely_accessible"] as? JsonPrimitive)?.booleanOrNull == true &&
            (path.startsWith("https://") || path.startsWith("http://"))
        return when {
            remote -> path to (proxy ?: path)
            proxy != null -> proxy to proxy
            else -> null
        }
    }

    /** Size asked of the proxy; the artwork loader tries a larger one first. */
    private const val PROXY_SIZE = 512

    /** Music Assistant plays spoken word from half to three times as fast. */
    private const val MIN_SPEED = 0.5
    private const val MAX_SPEED = 3.0

    /** More chapters than any book has; a list longer than this is cut, never trusted to be small. */
    private const val MAX_CHAPTERS = 1_000

}
