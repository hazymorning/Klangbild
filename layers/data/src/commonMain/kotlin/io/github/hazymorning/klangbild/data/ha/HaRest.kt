package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.HttpStatusException
import io.github.hazymorning.klangbild.data.address
import io.github.hazymorning.klangbild.data.bearer
import io.github.hazymorning.klangbild.data.fetch
import io.github.hazymorning.klangbild.data.textBelow
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.json.string
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** This TV as Home Assistant lists it among its mobile apps: its own name, what it is, and the version of Klangbild. */
data class HaDevice(
    val name: String,
    val model: String,
    val manufacturer: String,
    val osName: String,
    val osVersion: String,
    val appVersion: String,
)

/**
 * The parts of Home Assistant's REST API Klangbild uses
 * (https://developers.home-assistant.io/docs/api/rest): one entity's state, and all of them and
 * every action for the setup page; and the registration that lets Home Assistant send this TV
 * notifications. Requests carry the access token and go only to [HaAuth.baseUrl].
 */
class HaRest(private val http: HttpClient, private val auth: HaAuth) {
    /** What `GET /api/states/<entity_id>` found. */
    sealed interface Fetched {
        data class Found(val state: EntityState) : Fetched

        /** Home Assistant has no such entity (404). */
        data object Missing : Fetched

        /** No answer, or an answer that isn't a state. */
        data object Failed : Fetched
    }

    suspend fun state(entityId: String): Fetched = if (!EntityId.isValid(entityId)) Fetched.Missing else try {
        get("/api/states/$entityId") { code, text ->
            when (code) {
                200 -> objectOf(text)?.let(EntityState::of)?.let { Fetched.Found(it) } ?: Fetched.Failed
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

    /**
     * Registers this TV as one of Home Assistant's mobile apps (`POST /api/mobile_app/registrations`,
     * https://developers.home-assistant.io/docs/api/native-app-integration/setup), to be sent
     * notifications over the WebSocket, and returns the registration's webhook id. Registering again
     * with the same [deviceId] replaces the registration, so this TV stays one device there.
     */
    suspend fun register(device: HaDevice, deviceId: String): String {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("app_id", APP_ID)
            put("app_name", APP_NAME)
            put("app_version", device.appVersion)
            put("device_name", device.name)
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("os_name", device.osName)
            put("os_version", device.osVersion)
            put("supports_encryption", false)
            put("app_data", buildJsonObject { put("push_websocket_channel", true) })
        }
        return send("/api/mobile_app/registrations", body.toString()) { code, text ->
            if (code == 401 || code == 403) throw HaAuthRejectedException("HTTP $code")
            if (code != 200 && code != 201) throw HttpStatusException("Home Assistant", code)
            objectOf(text)?.string("webhook_id")?.takeIf { it.isNotBlank() } ?: throw IOException("No webhook id")
        }
    }

    private suspend fun all(path: String): String = get(path) { code, text ->
        if (code == 401 || code == 403) throw HaAuthRejectedException("HTTP $code")
        if (code != 200) throw HttpStatusException("Home Assistant", code)
        text
    }

    /** GET [path] with the access token; [read] gets the status and the body. */
    private suspend fun <T> get(path: String, read: (Int, String) -> T): T = send(path, null, read)

    /**
     * GET [path], or POST [json] to it, with the access token; [read] gets the status and the
     * body. A 401 with a signed-in token fetches a fresh token and tries once more.
     */
    private suspend fun <T> send(path: String, json: String?, read: (Int, String) -> T): T {
        val token = auth.accessToken()
        var response = request(path, json, token)
        if (response.first == 401 && auth.refreshable) {
            auth.invalidate(token)
            response = request(path, json, auth.accessToken())
        }
        val (code, text) = response
        return withContext(Dispatchers.Default) { read(code, text) }
    }

    /** GET [path], or POST [json] to it, with [token]: the status and the body. */
    private suspend fun request(path: String, json: String?, token: String): Pair<Int, String> = http.fetch(
        CALL_TIMEOUT_MS,
        {
            address(auth.baseUrl + path)
            bearer(token)
            if (json != null) {
                method = HttpMethod.Post
                setBody(TextContent(json, ContentType.Application.Json))
            }
        },
    ) { response -> response.status.value to response.textBelow(MAX_ANSWER_BYTES) }

    /** [text] as a JSON object; null when it isn't one, such as a proxy's error page. */
    private fun objectOf(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    companion object {
        /** One request gets this long, reading included. */
        const val CALL_TIMEOUT_MS = 15_000L

        /** All states of a large home are a few megabytes; far more is not Home Assistant. */
        const val MAX_ANSWER_BYTES = 32L * 1024 * 1024

        /** How Klangbild names itself among Home Assistant's mobile apps. */
        const val APP_ID = "io.github.hazymorning.klangbild"
        const val APP_NAME = "Klangbild"
    }
}
