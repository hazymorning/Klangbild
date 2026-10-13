package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.fetch
import io.github.hazymorning.klangbild.domain.EntityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The parts of Home Assistant's REST API Klangbild uses
 * (https://developers.home-assistant.io/docs/api/rest): one entity's state, and all of them and
 * every action for the setup page. Requests carry the access token and go only to [HaAuth.baseUrl].
 */
class HaRest(private val http: OkHttpClient, private val auth: HaAuth) {
    /** What `GET /api/states/<entity_id>` found. */
    sealed interface Fetched {
        data class Found(val state: EntityState) : Fetched

        /** Home Assistant has no such entity (404). */
        data object Missing : Fetched

        /** No answer, or not one that says anything. */
        data object Failed : Fetched
    }

    suspend fun state(entityId: String): Fetched = if (!EntityId.isValid(entityId)) Fetched.Missing else try {
        get("/api/states/$entityId") { code, text ->
            when (code) {
                200 -> (Json.parseToJsonElement(text) as? JsonObject)?.let(EntityState::of)?.let { Fetched.Found(it) } ?: Fetched.Failed
                404 -> Fetched.Missing
                else -> Fetched.Failed
            }
        }
    } catch (e: HaAuthRejectedException) {
        throw e
    } catch (_: IOException) {
        Fetched.Failed
    }

    /** Every state (`GET /api/states`) as raw JSON text, for the setup page's lists. */
    suspend fun allStates(): String = all("/api/states")

    /** Every action Home Assistant offers (`GET /api/services`) as raw JSON text: a script's own fields for the setup page. */
    suspend fun services(): String = all("/api/services")

    private suspend fun all(path: String): String = get(path) { code, text ->
        if (code == 401 || code == 403) throw HaAuthRejectedException("HTTP $code")
        if (code != 200) throw IOException("Home Assistant answered $code")
        text
    }

    /**
     * GET [path] with the access token; [read] gets the status and the body. A 401 with a
     * signed-in token fetches a fresh token and tries once more.
     */
    private suspend fun <T> get(path: String, read: (Int, String) -> T): T {
        var token = auth.accessToken()
        repeat(2) { attempt ->
            val request = Request.Builder()
                .url(auth.baseUrl + path)
                .header("Authorization", "Bearer $token")
                .build()
            val (code, text) = http.fetch(request, CALL_TIMEOUT_MS) { response ->
                val body = response.body.source().let { src ->
                    if (src.request(MAX_ANSWER_BYTES)) throw IOException("Answer too large") else src.readUtf8()
                }
                response.code to body
            }
            if (code == 401 && attempt == 0 && auth.refreshable) {
                auth.invalidate(token)
                token = auth.accessToken()
                return@repeat
            }
            return withContext(Dispatchers.Default) { read(code, text) }
        }
        throw IOException("Home Assistant refused the token")
    }

    companion object {
        /** One request gets this long, reading included. */
        const val CALL_TIMEOUT_MS = 15_000L

        /** All states of a large home are a few megabytes; far more is not Home Assistant. */
        const val MAX_ANSWER_BYTES = 32L * 1024 * 1024
    }
}
