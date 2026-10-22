package io.github.hazymorning.klangbild.setup

import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.config.OwnValues
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaTokens
import io.github.hazymorning.klangbild.data.ha.HaUrls
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One speaker offered on the setup page. */
data class SpeakerOption(val entityId: String, val name: String, val state: String, val musicAssistant: Boolean)

/** One light offered to join in. */
data class LightOption(val entityId: String, val name: String, val state: String)

/** One weather entity offered for the landscape's sky. */
data class WeatherOption(val entityId: String, val name: String, val state: String)

/**
 * One entity a button of one's own can work (a script, a scene, an automation, a button, a
 * switch); a script with the [fields] it declares.
 */
data class ActionOption(val entityId: String, val name: String, val fields: List<ScriptField> = emptyList())

/**
 * A field a script declares, as its selector asks for it ([type]: [TEXT], [NUMBER], [BOOLEAN],
 * [SELECT] with its [options] as value and label, [ENTITY] of its [domains]).
 */
data class ScriptField(
    val key: String,
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val type: String = TEXT,
    val options: List<Pair<String, String>> = emptyList(),
    val default: JsonElement? = null,
    val example: String? = null,
    val domains: List<String> = emptyList(),
) {
    companion object {
        const val TEXT = "text"
        const val NUMBER = "number"
        const val BOOLEAN = "boolean"
        const val SELECT = "select"
        const val ENTITY = "entity"
    }
}

/**
 * Any entity, for the values in the status line and the buttons' conditions: its raw [state]
 * and [unit], its attributes in words ([attributes]) and its lists by their count ([lists]).
 */
data class EntityOption(
    val entityId: String,
    val name: String,
    val state: String,
    val unit: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val lists: Map<String, Int> = emptyMap(),
)

sealed interface DiscoverResult {
    data class Ok(
        val speakers: List<SpeakerOption>,
        val lights: List<LightOption> = emptyList(),
        val weather: List<WeatherOption> = emptyList(),
        val actions: List<ActionOption> = emptyList(),
        val entities: List<EntityOption> = emptyList(),
    ) : DiscoverResult
    data class Failed(val message: String) : DiscoverResult
}

/**
 * The setup page's logic, independent of sockets: routes, the one-time code, the sign-in,
 * validation.
 *
 * Security model (home network, plain HTTP):
 *  - The server only runs while the setup screen is visible on the TV.
 *  - Every API call needs the six-digit code shown on the TV (also in the QR code). A wrong
 *    code is answered only after [WRONG_CODE_DELAY_MS], and after [MAX_FAILURES] a new code is
 *    drawn.
 *  - The sign-in uses Home Assistant's own login page (authorization code grant). The page
 *    asks for it with the code; the callback must bring back the random state of that one
 *    request, within [SIGN_IN_MS]. Its tokens never reach the browser.
 *  - Stored tokens are never sent back to the browser, and credentials are only kept for the
 *    address they were made for. The code Music Assistant pairs this TV with is given only to a
 *    call with the code, never written into the page itself.
 *
 * @param texts the words in the language shown.
 * @param clientId the client id Home Assistant knows this TV by: the setup page's own address.
 * @param discover lists what Home Assistant at an address offers, with the credentials given.
 * @param exchange swaps an authorization code for tokens at Home Assistant.
 * @param revoke revokes a refresh token that is no longer needed (best effort).
 * @param checkMa tries an address and token of Music Assistant (optional, for the whole queue).
 * @param maGuess Music Assistant's address as far as the TV can tell (from its picture links).
 * @param ownSpeaker this TV's own speaker in Home Assistant, while it plays as one: left out of the
 *   list, where "This TV" stands for it.
 * @param pairingToken the code Music Assistant pairs this TV with, while it plays as a speaker.
 */
class SetupHandler(
    private val settings: () -> Settings,
    val texts: () -> Texts,
    private val discover: suspend (url: String, credentials: HaCredentials) -> DiscoverResult,
    private val save: suspend (Settings) -> Unit,
    private val clientId: () -> String?,
    private val exchange: suspend (url: String, code: String, clientId: String) -> HaTokens,
    private val revoke: suspend (url: String, refreshToken: String) -> Unit = { _, _ -> },
    private val checkMa: suspend (url: String, token: String) -> MaLink = { _, _ -> MaLink.UNREACHABLE },
    private val maGuess: () -> String? = { null },
    private val ownSpeaker: () -> String? = { null },
    private val pairingToken: () -> String? = { null },
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    /** A wrong code is answered only after this long: guessing a code takes days, not minutes. */
    private val wrongCodeDelayMs: Long = WRONG_CODE_DELAY_MS,
) {
    @Volatile var code: String = newCode()
        private set
    private var failures = 0

    /** Called when the code changed (the TV redraws the QR code). */
    @Volatile var onCodeChanged: (String) -> Unit = {}

    /** A sign-in under way: Home Assistant's login page was opened for [url] with [state]. */
    private class Authorization(val state: String, val url: String, val clientId: String, val at: Long)

    /** A sign-in that worked, waiting to be saved with the rest of the form. */
    private class SignedIn(val url: String, val clientId: String, val refreshToken: String)

    @Volatile private var authorization: Authorization? = null
    @Volatile private var signedIn: SignedIn? = null

    private fun newCode(): String = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    @Synchronized
    fun rotateCode() {
        code = newCode()
        failures = 0
        onCodeChanged(code)
    }

    /** The setup is over (the screen closed): a sign-in that was never saved is revoked. */
    suspend fun reset() {
        authorization = null
        val dropped = signedIn
        signedIn = null
        if (dropped != null) revoke(dropped.url, dropped.refreshToken)
    }

    @Synchronized
    private fun codeMatches(candidate: String?): Boolean {
        val ok = candidate != null && MessageDigest.isEqual(candidate.trim().toByteArray(), code.toByteArray())
        if (!ok) {
            failures++
            if (failures >= MAX_FAILURES) rotateCode()
        } else {
            failures = 0
        }
        return ok
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(req: MiniHttp.Request): MiniHttp.Response {
        val t = texts()
        return when {
            req.method == "GET" && req.path == "/" ->
                MiniHttp.Response.html(SetupPage.html(t, prefilledCode = req.query["code"]?.takeIf { it.length == 6 && it.all(Char::isDigit) }))
            req.method == "GET" && req.path == "/favicon.ico" -> MiniHttp.Response(204, "image/x-icon", ByteArray(0))
            req.method == "GET" && req.path == CALLBACK_PATH -> callback(req)
            req.method == "POST" && req.path == "/api/state" -> withCode(req, t) { stateJson() }
            req.method == "POST" && req.path == "/api/signin" -> withCode(req, t) { body -> signInJson(body, t) }
            req.method == "POST" && req.path == "/api/discover" -> withCode(req, t) { body -> discoverJson(body, t) }
            req.method == "POST" && req.path == "/api/save" -> withCode(req, t) { body -> saveJson(body, t) }
            req.path.startsWith("/api/") -> MiniHttp.Response.json(405, error(t[R.string.setup_error_not_allowed]))
            else -> MiniHttp.Response.text(404, t[R.string.setup_error_not_found])
        }
    }

    private suspend fun withCode(req: MiniHttp.Request, t: Texts, block: suspend (JsonObject) -> MiniHttp.Response): MiniHttp.Response {
        val body = try {
            json.parseToJsonElement(req.bodyText.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return MiniHttp.Response.json(400, error(t[R.string.setup_error_request]))
        }
        if (!codeMatches(body.string("code"))) {
            delay(wrongCodeDelayMs)
            return MiniHttp.Response.json(403, error(t[R.string.setup_error_code]))
        }
        return block(body)
    }

    private fun stateJson(): MiniHttp.Response {
        val s = settings()
        val pending = signedIn
        val url = pending?.url ?: s.haUrl
        val auth = when {
            pending != null -> "signin"
            url == s.haUrl && s.haCredentials is HaCredentials.SignedIn -> "signin"
            url == s.haUrl && s.haCredentials is HaCredentials.LongLived -> "token"
            else -> "none"
        }
        return MiniHttp.Response.json(
            200,
            buildJsonObject {
                put("url", url ?: "")
                put("auth", auth)
                put("entity", s.entityId ?: "")
                put("sleepMinutes", s.sleepAfterMinutes)
                put("sleepAction", s.sleepAction.name.lowercase())
                put("clock", s.showClock)
                put("remote", s.remoteControl)
                put("maUrl", s.maUrl ?: maGuess() ?: "")
                put("hasMaToken", !s.maToken.isNullOrBlank())
                put("tvSpeaker", s.tvSpeaker)
                put("pairingToken", pairingToken() ?: "")
                put("lightsLeft", s.roomLights.left ?: "")
                put("lightsCenter", s.roomLights.center ?: "")
                put("lightsRight", s.roomLights.right ?: "")
                put("weather", s.weatherEntity ?: "")
                put("actions", buildJsonArray { s.quickActions.forEach { add(QuickActions.toJson(it)) } })
                put("values", buildJsonArray { s.ownValues.forEach { add(OwnValues.toJson(it)) } })
            }.toString(),
        )
    }

    /** Starts a sign-in: the address of Home Assistant's login page, for this request only. */
    private fun signInJson(body: JsonObject, t: Texts): MiniHttp.Response {
        val url = body.string("url")?.let { HaUrls.normalizeBase(it) }
            ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_address]))
        val client = clientId() ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_not_allowed]))
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(STATE_BYTES).also(random::nextBytes))
        authorization = Authorization(state, url, client, now())
        val authorize = "$url/auth/authorize?response_type=code" +
            "&client_id=${enc(client)}&redirect_uri=${enc(redirectUri(client))}&state=${enc(state)}"
        return MiniHttp.Response.json(200, buildJsonObject {
            put("ok", true)
            put("authorize", authorize)
        }.toString())
    }

    /**
     * Home Assistant's login page sends the phone back here with a code for the tokens. Only the
     * state of the one sign-in under way is taken, once. The phone goes on to the setup page.
     */
    private suspend fun callback(req: MiniHttp.Request): MiniHttp.Response {
        val pending = authorization
        val state = req.query["state"]
        val authCode = req.query["code"]
        if (pending == null || state == null || authCode == null) return redirect(SIGN_IN_FAILED)
        val valid = MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray()) && now() - pending.at <= SIGN_IN_MS
        if (!valid) return redirect(SIGN_IN_FAILED)
        authorization = null
        val tokens = try {
            exchange(pending.url, authCode, pending.clientId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return redirect(SIGN_IN_FAILED)
        }
        val refresh = tokens.refreshToken ?: return redirect(SIGN_IN_FAILED)
        signedIn?.let { revoke(it.url, it.refreshToken) }
        signedIn = SignedIn(pending.url, pending.clientId, refresh)
        return redirect(SIGN_IN_OK)
    }

    /**
     * The credentials the form means for [url]: a token pasted now, else the sign-in just made,
     * else what is stored for that address.
     */
    private fun credentialsFor(url: String, body: JsonObject): HaCredentials? {
        body.string("token")?.trim()?.takeIf { it.isNotEmpty() }?.let { return HaCredentials.LongLived(it) }
        signedIn?.takeIf { it.url == url }?.let { return HaCredentials.SignedIn(it.refreshToken, it.clientId) }
        return settings().takeIf { it.haUrl == url }?.haCredentials
    }

    private suspend fun discoverJson(body: JsonObject, t: Texts): MiniHttp.Response {
        val url = body.string("url")?.let { HaUrls.normalizeBase(it) }
            ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_address]))
        val credentials = credentialsFor(url, body) ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_credentials]))
        return when (val r = discover(url, credentials)) {
            is DiscoverResult.Failed -> MiniHttp.Response.json(200, buildJsonObject {
                put("ok", false)
                put("message", r.message)
            }.toString())
            is DiscoverResult.Ok -> MiniHttp.Response.json(200, buildJsonObject {
                put("ok", true)
                put("url", url)
                put("speakers", buildJsonArray {
                    val own = ownSpeaker()
                    r.speakers.filter { it.entityId != own }.forEach { sp ->
                        add(buildJsonObject {
                            put("id", sp.entityId)
                            put("name", sp.name)
                            put("state", sp.state)
                            put("ma", sp.musicAssistant)
                        })
                    }
                })
                put("lights", buildJsonArray {
                    r.lights.forEach { l ->
                        add(buildJsonObject {
                            put("id", l.entityId)
                            put("name", l.name)
                            put("state", l.state)
                        })
                    }
                })
                put("weather", buildJsonArray {
                    r.weather.forEach { w ->
                        add(buildJsonObject {
                            put("id", w.entityId)
                            put("name", w.name)
                            put("state", w.state)
                        })
                    }
                })
                put("actions", buildJsonArray {
                    r.actions.forEach { a ->
                        add(buildJsonObject {
                            put("id", a.entityId)
                            put("name", a.name)
                            if (a.fields.isNotEmpty()) put("fields", buildJsonArray { a.fields.forEach { add(fieldJson(it)) } })
                        })
                    }
                })
                put("entities", buildJsonArray {
                    r.entities.forEach { e ->
                        add(buildJsonObject {
                            put("id", e.entityId)
                            put("name", e.name)
                            put("state", e.state)
                            e.unit?.let { put("unit", it) }
                            if (e.attributes.isNotEmpty()) put("attrs", JsonObject(e.attributes.mapValues { JsonPrimitive(it.value) }))
                            if (e.lists.isNotEmpty()) put("lists", JsonObject(e.lists.mapValues { JsonPrimitive(it.value) }))
                        })
                    }
                })
            }.toString())
        }
    }

    private fun fieldJson(f: ScriptField) = buildJsonObject {
        put("key", f.key)
        put("name", f.name)
        f.description?.let { put("description", it) }
        if (f.required) put("required", true)
        put("type", f.type)
        if (f.options.isNotEmpty()) {
            put("options", buildJsonArray {
                f.options.forEach { (value, label) ->
                    add(buildJsonObject {
                        put("value", value)
                        put("label", label)
                    })
                }
            })
        }
        f.default?.let { put("default", it) }
        f.example?.let { put("example", it) }
        if (f.domains.isNotEmpty()) put("domains", buildJsonArray { f.domains.forEach { add(JsonPrimitive(it)) } })
    }

    private suspend fun saveJson(body: JsonObject, t: Texts): MiniHttp.Response {
        val current = settings()
        val url = body.string("url")?.let { HaUrls.normalizeBase(it) }
            ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_address]))
        val credentials = credentialsFor(url, body) ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_credentials]))
        val ha = current.withHa(url, credentials)
        val withMa = maSettings(ha, body) ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_ma_address]))
        if (withMa.maUrl != null && withMa.maToken == null) return MiniHttp.Response.json(400, error(t[R.string.setup_error_ma_token]))
        if (withMa.hasMusicAssistant && (withMa.maUrl != current.maUrl || withMa.maToken != current.maToken)) {
            when (checkMa(withMa.maUrl!!, withMa.maToken!!)) {
                MaLink.CONNECTED -> Unit
                MaLink.AUTH_FAILED -> return MiniHttp.Response.json(400, error(t[R.string.setup_error_ma_rejected]))
                MaLink.UNREACHABLE -> return MiniHttp.Response.json(400, error(t[R.string.setup_error_ma_unreachable]))
            }
        }
        val tvSpeaker = body.bool("tvSpeaker") ?: current.tvSpeaker
        val entity = body.string("entity")?.trim()?.takeIf { EntityId.isValid(it) || it == EntityId.THIS_TV }
            ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_speaker]))
        // "This TV" follows the TV's own speaker, which needs Music Assistant and the TV playing for it.
        if (entity == EntityId.THIS_TV && !(tvSpeaker && withMa.hasMusicAssistant)) return MiniHttp.Response.json(400, error(t[R.string.setup_error_this_tv]))
        val minutes = (body["sleepMinutes"] as? JsonPrimitive)?.intOrNull ?: body.string("sleepMinutes")?.toIntOrNull()
            ?: current.sleepAfterMinutes
        if (minutes !in 1..Settings.MAX_SLEEP_MINUTES) return MiniHttp.Response.json(400, error(t[R.string.setup_error_sleep]))
        val action = when (body.string("sleepAction")) {
            "close" -> SleepAction.CLOSE
            "release" -> SleepAction.RELEASE
            else -> current.sleepAction
        }
        // The lights only when the page showed them (it found the lights); "" is none.
        val lightKeys = listOf("lightsLeft", "lightsCenter", "lightsRight")
        val roomLights = if (lightKeys.none { it in body }) {
            current.roomLights
        } else {
            val ids = lightKeys.map { k -> body.string(k)?.trim().orEmpty() }
            if (ids.any { it.isNotEmpty() && !EntityId.isLight(it) }) return MiniHttp.Response.json(400, error(t[R.string.setup_error_lights]))
            RoomLights(ids[0].ifEmpty { null }, ids[1].ifEmpty { null }, ids[2].ifEmpty { null })
        }
        // The landscape's weather: "" finds Home Assistant's own, "none" keeps the sky fair; absent stays as it was.
        val weather = when (val w = body.string("weather")?.trim()) {
            null -> current.weatherEntity
            "" -> null
            Settings.NO_WEATHER -> Settings.NO_WEATHER
            else -> w.takeIf { EntityId.isWeather(it) }
                ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_weather]))
        }
        // The quick settings' own buttons only when the page showed them; an empty list removes them.
        val actions = when (val list = body["actions"]) {
            null -> current.quickActions
            is JsonArray -> list.take(QuickActions.MAX).map { e ->
                QuickActions.from(e as? JsonObject) ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_actions]))
            }
            else -> return MiniHttp.Response.json(400, error(t[R.string.setup_error_actions]))
        }
        // The values in the status line, likewise.
        val values = when (val list = body["values"]) {
            null -> current.ownValues
            is JsonArray -> list.take(OwnValues.MAX).map { e ->
                OwnValues.from(e as? JsonObject) ?: return MiniHttp.Response.json(400, error(t[R.string.setup_error_values]))
            }
            else -> return MiniHttp.Response.json(400, error(t[R.string.setup_error_values]))
        }
        val updated = withMa.copy(
            weatherEntity = weather,
            entityId = entity,
            tvSpeaker = tvSpeaker,
            sleepAfterMinutes = minutes,
            sleepAction = action,
            showClock = body.bool("clock") ?: current.showClock,
            remoteControl = body.bool("remote") ?: current.remoteControl,
            roomLights = roomLights,
            quickActions = actions,
            ownValues = values,
        )
        // The sign-in becomes the connection's: it is no longer waiting, before saving ends the
        // setup (whose reset revokes a sign-in still waiting). One it replaced is revoked.
        val taken = signedIn?.takeIf { it.refreshToken == (credentials as? HaCredentials.SignedIn)?.refreshToken }
        if (taken != null) signedIn = null
        try {
            save(updated)
        } catch (e: Exception) {
            if (taken != null && signedIn == null) signedIn = taken
            throw e
        }
        val old = current.haCredentials as? HaCredentials.SignedIn
        val oldUrl = current.haUrl
        if (old != null && oldUrl != null && old != updated.haCredentials) revoke(oldUrl, old.refreshToken)
        return MiniHttp.Response.json(200, buildJsonObject {
            put("ok", true)
            put("message", t[R.string.setup_saved])
        }.toString())
    }

    /** [s] with Music Assistant's address and token from the form; null for an address that is none. */
    private fun maSettings(s: Settings, body: JsonObject): Settings? {
        val raw = body.string("maUrl")?.trim().orEmpty()
        if (raw.isEmpty()) return s.withMa(null, null)
        val url = HaUrls.normalizeBase(raw) ?: return null
        return s.withMa(url, body.string("maToken")?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun redirect(location: String) = MiniHttp.Response(303, "text/plain; charset=utf-8", ByteArray(0), mapOf("Location" to location))

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("message", message)
    }.toString()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val MAX_FAILURES = 8

        /** How long a wrong code waits for its answer (ms). */
        const val WRONG_CODE_DELAY_MS = 1_000L

        /** Where Home Assistant's login page sends the phone back to. */
        const val CALLBACK_PATH = "/auth/callback"

        /** Where the phone goes on to after the sign-in. */
        const val SIGN_IN_OK = "/?signin=ok"
        const val SIGN_IN_FAILED = "/?signin=failed"

        /** A sign-in must come back within this long (Home Assistant's codes last ten minutes). */
        const val SIGN_IN_MS = 10 * 60_000L

        /** The random state of a sign-in (bytes). */
        private const val STATE_BYTES = 32

        /** The redirect URI that belongs to [clientId]: the same host and port, as Home Assistant requires. */
        fun redirectUri(clientId: String): String = clientId.trimEnd('/') + CALLBACK_PATH
    }
}
