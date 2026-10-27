package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerAbilities
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueueInfo
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToLong

/** Maps Home Assistant entity states and Music Assistant queue responses to domain types. */
object PlayerParser {

    fun status(state: String): PlayerStatus = when (state) {
        "playing" -> PlayerStatus.PLAYING
        "paused" -> PlayerStatus.PAUSED
        "buffering" -> PlayerStatus.BUFFERING
        "idle", "on", "standby" -> PlayerStatus.IDLE
        "off" -> PlayerStatus.OFF
        "unavailable" -> PlayerStatus.UNAVAILABLE
        else -> PlayerStatus.UNKNOWN
    }

    /** ISO-8601 → epoch millis; HA omits fractional seconds when they are zero. */
    fun parseInstant(iso: String?): Long? = iso?.let(EntityState::instant)?.toEpochMilliseconds()

    fun player(entity: EntityState, baseUrl: String): PlayerSnapshot {
        val status = status(entity.state)
        val title = entity.string("media_title")?.trim().orEmpty()
        val track = if (title.isEmpty() || status == PlayerStatus.OFF || status == PlayerStatus.UNAVAILABLE) {
            null
        } else {
            val picture = HaUrls.resolve(baseUrl, entity.string("entity_picture"))
            val localPicture = HaUrls.resolve(baseUrl, entity.string("entity_picture_local"))
            var source = entity.string("media_channel") ?: entity.string("source")?.takeIf { entity.string("app_id") == "radio" }
            val kind = MediaKind.fromUri(entity.string("media_content_id")) ?: if (source != null) MediaKind.RADIO else MediaKind.MUSIC
            // Music Assistant puts an item's description where the album would be when it has
            // none. For a book that is its blurb, paragraphs long. That is never an album line.
            var album = entity.string("media_album_name")?.trim()?.takeIf { it.isNotEmpty() && !isDescription(it) && kind != MediaKind.AUDIOBOOK }
            // A station: Music Assistant names it where the album would be, unless the song brings
            // its own album (then the queue names the station). It is the source, not an album.
            if (kind == MediaKind.RADIO && source == null) {
                source = album
                album = null
            }
            var artists = if (kind == MediaKind.AUDIOBOOK) splitAuthors(entity.string("media_artist")) else splitArtists(entity.string("media_artist"))
            // An episode: the show's name (in the album's place) stands where an artist would.
            if (kind == MediaKind.PODCAST && artists.isEmpty() && album != null) {
                artists = listOf(album)
                album = null
            }
            TrackInfo(
                title = title,
                artists = artists,
                album = album,
                durationMs = entity.number("media_duration")?.takeIf { it > 0 }?.let { (it * 1000).roundToLong() },
                artworkUrl = picture?.let { ArtworkUrls.upgrade(it) },
                artworkFallbackUrl = localPicture ?: picture,
                source = source,
                kind = kind,
            )
        }
        val isMa = entity.string("app_id") == "music_assistant" || entity.attr("mass_player_type") != null
        return PlayerSnapshot(
            status = status,
            track = track,
            positionMs = entity.number("media_position")?.let { (it * 1000).roundToLong() },
            positionAtEpochMs = parseInstant(entity.string("media_position_updated_at")),
            friendlyName = entity.string("friendly_name"),
            activeQueue = entity.string("active_queue"),
            isMusicAssistant = isMa,
            groupMembers = groupMembers(entity),
            volume = entity.number("volume_level")?.toFloat()?.coerceIn(0f, 1f),
            muted = (entity.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true,
            isGroupPlayer = entity.string("mass_player_type") == "group",
            shuffle = shuffleOf(entity),
            abilities = abilitiesOf(entity),
        )
    }

    /** Shuffle as the player reports it, or null when it cannot shuffle (`supported_features` lacks SHUFFLE_SET). */
    fun shuffleOf(entity: EntityState): Boolean? {
        val on = (entity.attr("shuffle") as? JsonPrimitive)?.booleanOrNull
        val features = entity.number("supported_features")?.toLong() ?: return on
        return if (features and Feature.SHUFFLE_SET != 0L) on ?: false else null
    }

    /** What the player supports (`supported_features`); everything when it does not say. */
    fun abilitiesOf(entity: EntityState): PlayerAbilities {
        val f = entity.number("supported_features")?.toLong() ?: return PlayerAbilities()
        fun has(flag: Long) = f and flag != 0L
        return PlayerAbilities(
            play = has(Feature.PLAY),
            pause = has(Feature.PAUSE),
            seek = has(Feature.SEEK),
            next = has(Feature.NEXT_TRACK),
            previous = has(Feature.PREVIOUS_TRACK),
            volume = has(Feature.VOLUME_SET) || has(Feature.VOLUME_STEP),
            shuffle = has(Feature.SHUFFLE_SET),
        )
    }

    /**
     * Home Assistant's `MediaPlayerEntityFeature` flags that the remote's actions need
     * (https://developers.home-assistant.io/docs/core/entity/media-player).
     */
    object Feature {
        const val PAUSE = 1L
        const val SEEK = 2L
        const val VOLUME_SET = 4L
        const val PREVIOUS_TRACK = 16L
        const val NEXT_TRACK = 32L
        const val VOLUME_STEP = 1_024L
        const val PLAY = 16_384L
        const val SHUFFLE_SET = 32_768L
    }

    /**
     * `group_members` lists every speaker of a sync group, the leader first. It is the same on the
     * leader and on each member. The others are the list without this speaker.
     */
    fun groupMembers(entity: EntityState): List<String> =
        (entity.attr("group_members") as? JsonArray)
            ?.mapNotNull { it.string()?.trim() }
            ?.filter { it.isNotEmpty() && it != entity.entityId }
            ?.distinct()
            .orEmpty()

    /** MA joins multiple artists with " / " in `media_artist`. */
    fun splitArtists(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return emptyList()
        return s.split(" / ", "/ ", " /").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** A book's authors, which Music Assistant joins with a bare "/" (no band is called "AC/DC" here). */
    fun splitAuthors(raw: String?): List<String> =
        raw?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** Text too long, or in paragraphs, to be an album's name: a description in its place. */
    fun isDescription(s: String): Boolean = s.length > MAX_ALBUM_CHARS || s.contains('\n')

    /** Longer than any album name worth a line (they do reach a hundred characters). */
    private const val MAX_ALBUM_CHARS = 160

    /**
     * Parses `music_assistant.get_queue` (with `return_response`). The response is keyed by
     * entity id: `{"media_player.x": {"current_item": {...}, "next_item": {...}}}`.
     */
    fun queue(response: JsonObject, entityId: String, baseUrl: String): QueueInfo? {
        val q = (response[entityId] as? JsonObject) ?: (response.values.firstOrNull() as? JsonObject) ?: return null
        val current = q["current_item"] as? JsonObject
        val next = q["next_item"] as? JsonObject
        return QueueInfo(
            current = current?.let { queueItem(it, baseUrl) },
            next = next?.let { queueItem(it, baseUrl) },
            name = q.string("name")?.trim()?.takeIf { it.isNotEmpty() },
            queueId = q.string("queue_id")?.takeIf { it.isNotBlank() },
            currentIndex = (q["current_index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 },
        )
    }

    private fun queueItem(item: JsonObject, baseUrl: String): TrackInfo? {
        val media = item["media_item"] as? JsonObject
        val name = media?.string("name") ?: item.string("name") ?: return null
        val version = media?.string("version")?.takeIf { it.isNotBlank() }
        val artists = (media?.get("artists") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("name") }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val album = media?.get("album") as? JsonObject
        val image = media?.string("image") ?: album?.string("image")
        val resolved = HaUrls.resolve(baseUrl, image)
        val year = (album?.get("year") as? JsonPrimitive)?.intOrNull
        val kind = MediaKind.fromType(media?.string("media_type")) ?: MediaKind.MUSIC
        val isRadio = kind == MediaKind.RADIO
        // Radio: the station is the source, and the stream title (if any) is what plays now: its
        // song's title after the artist (the two joined by a spaced hyphen), as Music Assistant splits it too.
        val streamTitle = item.string("stream_title")?.takeIf { it.isNotBlank() }
        val title = when {
            isRadio -> streamTitle?.let { t -> t.substringAfter(" - ", t).trim().ifEmpty { t } } ?: name
            version != null -> "$name ($version)"
            else -> name
        }
        return TrackInfo(
            title = title,
            artists = artists,
            album = album?.string("name")?.takeIf { it.isNotBlank() },
            year = year,
            durationMs = (item["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { (it * 1000).roundToLong() },
            artworkUrl = resolved?.let { ArtworkUrls.upgrade(it) },
            artworkFallbackUrl = resolved,
            source = if (isRadio) name else null,
            kind = kind,
        )
    }

}
