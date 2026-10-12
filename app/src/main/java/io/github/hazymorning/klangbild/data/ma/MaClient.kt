package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.data.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How the optional link to Music Assistant stands, for the menu, never as raw errors. */
enum class MaLink {
    CONNECTED,

    /** Music Assistant rejected the token (expired after a year, or deleted). */
    AUTH_FAILED,

    /** No answer, or not the answer of a Music Assistant server. */
    UNREACHABLE,
}

/** Music Assistant rejected the token. */
class MaAuthException : IOException("Music Assistant rejected the token")

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
class MaClient(private val http: OkHttpClient, val baseUrl: String, private val token: String) {

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
     * Adds what [playerId] plays to the favorites (a station's song looked up as a song);
     * throws when Music Assistant cannot.
     */
    suspend fun favoriteNowPlaying(playerId: String) {
        command("players/add_currently_playing_to_favorites", buildJsonObject { put("player_id", playerId) })
    }

    /** Whether address and token work, as the menu and the setup page say it. */
    suspend fun check(): MaLink = try {
        if (command("player_queues/all", JsonObject(emptyMap())) is JsonArray) MaLink.CONNECTED else MaLink.UNREACHABLE
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
    suspend fun partyPlayer(): String? = text(command("party/player", JsonObject(emptyMap())))

    /**
     * The link guests open to join the party (`party/url`); null while guest access is off. Music
     * Assistant hands out the same link until its code expires, then a new one.
     */
    suspend fun partyUrl(): String? = text(command("party/url", JsonObject(emptyMap())))

    private fun text(answer: JsonElement): String? = (answer as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /**
     * The words Music Assistant has for [track] (a queue item's `media_item`): plain and with
     * times (`metadata/get_track_lyrics`, Music Assistant 2.8 and later). It may look them up
     * online first, which it does at most once in 30 seconds, so the answer may take a while:
     * the call has a timeout of its own ([LYRICS_TIMEOUT_MS]). Throws [MaUnknownCommandException]
     * on a version without the command.
     */
    suspend fun lyrics(track: JsonObject): Pair<String?, String?> {
        val answer = command(LYRICS_COMMAND, buildJsonObject { put("track", track) }, slowHttp, LYRICS_TIMEOUT_MS)
        fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (answer) {
            is JsonArray -> answer.getOrNull(0).text() to answer.getOrNull(1).text()
            is JsonObject -> answer["lyrics"].text() to answer["lrc_lyrics"].text()
            else -> null to null
        }
    }

    /** The same connections, with room for an answer that takes a while (the lyrics). */
    private val slowHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(LYRICS_TIMEOUT_MS, TimeUnit.MILLISECONDS).build() }

    /** One command; canceling the coroutine cancels the request. */
    private suspend fun command(
        name: String,
        args: JsonObject,
        client: OkHttpClient = http,
        timeoutMs: Long = CALL_TIMEOUT_MS,
    ): JsonElement {
        val body = buildJsonObject {
            put("message_id", name)
            put("command", name)
            put("args", args)
        }
        val request = Request.Builder()
            .url("$baseUrl/api")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = client.fetch(request, timeoutMs) { response ->
            if (response.code == 401 || response.code == 403) throw MaAuthException()
            // An older version says so in words: "Invalid Command: …".
            if (response.code == 400 && response.peekBody(UNKNOWN_PEEK_BYTES).string().startsWith("Invalid Command")) throw MaUnknownCommandException(name)
            if (!response.isSuccessful) throw IOException("Music Assistant answered ${response.code}")
            response.body.source().let { src ->
                if (!src.request(MAX_ANSWER_BYTES)) src.readUtf8() else throw IOException("Answer too large")
            }
        }
        return withContext(Dispatchers.Default) { Json.parseToJsonElement(text) }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private val proxyLink = Regex("""^(https?://[^/?#]+)/imageproxy[/?]""")

        /** Music Assistant's address as one of its picture links gives it away (`http://host:8095/imageproxy/…`). */
        fun addressIn(pictureUrl: String?): String? = pictureUrl?.let { proxyLink.find(it)?.groupValues?.get(1) }

        /** A queue window is a few hundred kilobytes at most; anything far larger is not ours. */
        const val MAX_ANSWER_BYTES = 4L * 1024 * 1024

        /** One command gets this long, reading included. */
        const val CALL_TIMEOUT_MS = 10_000L

        const val LYRICS_COMMAND = "metadata/get_track_lyrics"

        /**
         * The words get this long: Music Assistant asks the online service it looks them up in
         * at most once in 30 seconds, so an answer may wait for that, and for one before it.
         */
        const val LYRICS_TIMEOUT_MS = 75_000L

        /** Enough of a refusal to read its first words. */
        private const val UNKNOWN_PEEK_BYTES = 256L
    }
}
