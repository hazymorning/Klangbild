package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.data.HttpStatusException
import io.github.hazymorning.klangbild.data.address
import io.github.hazymorning.klangbild.data.bearer
import io.github.hazymorning.klangbild.data.bodyBytes
import io.github.hazymorning.klangbild.data.fetch
import io.github.hazymorning.klangbild.data.textBelow
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.json.string
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HeaderValueParam
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Music Assistant rejected the token. */
class MaAuthException : IOException("Music Assistant rejected the token")

/** A track's lyrics as Music Assistant has them: [plain] text, and [lrc] with the times of its lines. */
data class TrackLyrics(val plain: String?, val lrc: String?)

/** Music Assistant does not know the command (a version from before it). */
class MaUnknownCommandException(command: String) : IOException("Music Assistant does not know $command")

/**
 * Music Assistant's own API, for what Home Assistant does not pass on: the whole queue, meaning
 * what was played before the current song and what follows it, and a song's words. Optional:
 * without an address and a token (from the setup page) Klangbild works from Home Assistant alone.
 *
 * One HTTP call per command (`POST <base>/api`, the long-lived token as bearer), only to the
 * address set up; the cleartext guard of the shared client keeps plain HTTP inside the home
 * network.
 */
class MaClient(
    private val http: HttpClient,
    val baseUrl: String,
    private val token: String,
    /** How long a command may take, reading included. */
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    /** How long a lyrics request may take, and wait for its next bytes. */
    private val lyricsTimeoutMs: Long = LYRICS_TIMEOUT_MS,
) {

    /** The items of queue [queueId] from [offset], at most [limit], in play order. */
    suspend fun items(queueId: String, offset: Int, limit: Int): JsonArray {
        val result = command(
            "player_queues/items",
            buildJsonObject {
                put("queue_id", queueId)
                put("offset", offset)
                put("limit", limit)
            },
        )
        return result as? JsonArray ?: throw IOException("Unexpected answer to player_queues/items")
    }

    /**
     * Queue [queueId] as Music Assistant has it now (`player_queues/get`): where it stands, its
     * current and next item. It keeps them up to date while a song plays, even when Home
     * Assistant has heard of no change. Null when it does not know the queue.
     */
    suspend fun queue(queueId: String): JsonObject? =
        command("player_queues/get", buildJsonObject { put("queue_id", queueId) }) as? JsonObject

    /**
     * Adds what [playerId] plays to the favorites (a station's song looked up as a song);
     * throws when Music Assistant cannot.
     */
    suspend fun favoriteNowPlaying(playerId: String) {
        command("players/add_currently_playing_to_favorites", buildJsonObject { put("player_id", playerId) })
    }

    /**
     * The id of the player Music Assistant plays to through the Sendspin client [clientId]
     * (`players/all`): the player around it, which lists it among its outputs, or the client
     * itself. Null while Music Assistant has none (it names a new speaker's player a few
     * seconds after it first connects).
     */
    suspend fun playerOf(clientId: String): String? {
        val players = command("players/all", JsonObject(emptyMap())) as? JsonArray ?: throw IOException("Unexpected answer to players/all")
        return players.firstNotNullOfOrNull { e ->
            val p = e as? JsonObject ?: return@firstNotNullOfOrNull null
            val id = p["player_id"].string() ?: return@firstNotNullOfOrNull null
            val outputs = p["output_protocols"] as? JsonArray
            id.takeIf { it == clientId || outputs?.any { (it as? JsonObject)?.get("output_protocol_id").string() == clientId } == true }
        }
    }

    /** Music Assistant's own clock (`time`, its wall clock in seconds), for its queue's times. */
    suspend fun serverTime(): Double =
        (command("time", JsonObject(emptyMap())) as? JsonPrimitive)?.doubleOrNull ?: throw IOException("Unexpected answer to time")

    /** Whether address and token work, as the menu and the setup page say it. */
    suspend fun check(): MaLink = try {
        if (command("player_queues/all", JsonObject(emptyMap())) is JsonArray) MaLink.CONNECTED else MaLink.UNREACHABLE
    } catch (e: CancellationException) {
        throw e
    } catch (_: MaAuthException) {
        MaLink.AUTH_FAILED
    } catch (_: Exception) {
        MaLink.UNREACHABLE
    }

    /**
     * The queue a party of Music Assistant's Party plugin plays to (`party/player`), while its
     * guests may join; null while guest access is off. Throws [MaUnknownCommandException] where
     * the plugin is not there.
     */
    suspend fun partyPlayer(): String? = command("party/player", JsonObject(emptyMap())).string()?.takeIf { it.isNotBlank() }

    /**
     * The link guests open to join the party (`party/url`); null while guest access is off. Music
     * Assistant hands out the same link until its code expires, then a new one.
     */
    suspend fun partyUrl(): String? = command("party/url", JsonObject(emptyMap())).string()?.takeIf { it.isNotBlank() }

    /**
     * The words Music Assistant has for [track] (a queue item's `media_item`): plain and with
     * times (`metadata/get_track_lyrics`, Music Assistant 2.8 and later). It may look them up
     * online first, which it does at most once in 30 seconds, so the answer may take a while:
     * the call has a timeout of its own ([lyricsTimeoutMs]). Throws [MaUnknownCommandException]
     * on a version without the command.
     */
    suspend fun lyrics(track: JsonObject): TrackLyrics {
        val args = buildJsonObject { put("track", track) }
        return when (val answer = command(LYRICS_COMMAND, args, timeoutMs = lyricsTimeoutMs, readTimeoutMs = lyricsTimeoutMs)) {
            is JsonArray -> TrackLyrics(answer.getOrNull(0).string(), answer.getOrNull(1).string())
            is JsonObject -> TrackLyrics(answer["lyrics"].string(), answer["lrc_lyrics"].string())
            else -> TrackLyrics(null, null)
        }
    }

    /**
     * One command; canceling the coroutine cancels the request. It may take [timeoutMs], reading
     * included, and wait [readTimeoutMs] for the next bytes instead of as long as the engine lets it.
     */
    private suspend fun command(name: String, args: JsonObject, timeoutMs: Long = callTimeoutMs, readTimeoutMs: Long? = null): JsonElement {
        val body = buildJsonObject {
            put("message_id", name)
            put("command", name)
            put("args", args)
        }
        val text = http.fetch(
            timeoutMs,
            {
                method = HttpMethod.Post
                address("$baseUrl/api")
                bearer(token)
                setBody(TextContent(body.toString(), JSON))
            },
            readTimeoutMs = readTimeoutMs,
        ) { response ->
            val code = response.status.value
            if (code == 401 || code == 403) throw MaAuthException()
            // An older version says so in words: "Invalid Command: …".
            if (code == 400 && response.bodyBytes(UNKNOWN_PEEK_BYTES).decodeToString().startsWith("Invalid Command")) throw MaUnknownCommandException(name)
            if (!response.status.isSuccess()) throw HttpStatusException("Music Assistant", code)
            response.textBelow(MAX_ANSWER_BYTES)
        }
        return withContext(Dispatchers.Default) { Json.parseToJsonElement(text) }
    }

    companion object {
        /** JSON in UTF-8, as the commands are sent. */
        private val JSON = ContentType("application", "json", listOf(HeaderValueParam("charset", "utf-8")))

        private val proxyLink = Regex("""^(https?://[^/?#]+)/imageproxy[/?]""")

        /** Music Assistant's address as one of its picture links gives it away (`http://host:8095/imageproxy/…`). */
        fun addressIn(pictureUrl: String?): String? = pictureUrl?.let { proxyLink.find(it)?.groupValues?.get(1) }

        /** A queue window is a few hundred kilobytes at most; anything far larger is not ours. */
        const val MAX_ANSWER_BYTES = 4L * 1024 * 1024

        private const val CALL_TIMEOUT_MS = 10_000L

        private const val LYRICS_COMMAND = "metadata/get_track_lyrics"

        /**
         * Timeout for a lyrics request. Music Assistant queries its online lyrics service at most
         * once every 30 seconds, so an answer may wait for one or two of those slots.
         */
        private const val LYRICS_TIMEOUT_MS = 75_000L

        /** Enough of a refusal to read its first words. */
        private const val UNKNOWN_PEEK_BYTES = 256L
    }
}
