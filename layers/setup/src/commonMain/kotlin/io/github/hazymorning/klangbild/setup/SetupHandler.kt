package io.github.hazymorning.klangbild.setup

import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.hazymorning.klangbild.config.OwnValues
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.data.formEncode
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaTokens
import io.github.hazymorning.klangbild.data.ha.HaUrls
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.json.boolean
import io.github.hazymorning.klangbild.json.int
import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

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
    private val random: Random = CryptographyRandom,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** A wrong code is answered only after this long: guessing a code takes days, not minutes. */
    private val wrongCodeDelayMs: Long = WRONG_CODE_DELAY_MS,
) {
    @Volatile var code: String = newCode()
        private set
    private var failures = 0
    private val lock = SynchronizedObject()

    /** Called when the code changed (the TV redraws the QR code). */
    @Volatile var onCodeChanged: (String) -> Unit = {}

    /** A sign-in under way: Home Assistant's login page was opened for [url] with [state] while [setupsEnded] was [setup]. */
    private class Authorization(val state: String, val url: String, val clientId: String, val at: Long, val setup: Int)

    /** A sign-in that worked, waiting to be saved with the rest of the form. */
    private class SignedIn(val url: String, val clientId: String, val refreshToken: String)

    @Volatile private var authorization: Authorization? = null
    @Volatile private var signedIn: SignedIn? = null

    /** Counts the [reset]s, guarded by [lock]: a sign-in is kept only in the setup it began in. */
    private var setupsEnded = 0

    private fun newCode(): String = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    fun rotateCode(): Unit = synchronized(lock) {
        code = newCode()
        failures = 0
        onCodeChanged(code)
    }

    /** The setup is over (the screen closed): a sign-in that was never saved is revoked, now or once it arrives. */
    suspend fun reset() {
        val dropped = synchronized(lock) {
            setupsEnded++
            authorization = null
            signedIn.also { signedIn = null }
        }
        if (dropped != null) revoke(dropped.url, dropped.refreshToken)
    }

    private fun codeMatches(candidate: String?): Boolean = synchronized(lock) {
        val ok = candidate != null && same(candidate.trim().encodeToByteArray(), code.encodeToByteArray())
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
            req.path.startsWith("/api/") -> MiniHttp.Response.json(405, error(t[Strings.setupErrorNotAllowed]))
            else -> MiniHttp.Response.text(404, t[Strings.setupErrorNotFound])
        }
    }

    private suspend fun withCode(req: MiniHttp.Request, t: Texts, block: suspend (JsonObject) -> MiniHttp.Response): MiniHttp.Response {
        val body = try {
            json.parseToJsonElement(req.bodyText.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return refused(t[Strings.setupErrorRequest])
        }
        if (!codeMatches(body.string("code"))) {
            delay(wrongCodeDelayMs)
            return MiniHttp.Response.json(403, error(t[Strings.setupErrorCode]))
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
                put("otherRooms", buildJsonArray { s.otherRooms.forEach { add(JsonPrimitive(it)) } })
                put("presence", buildJsonArray { s.presenceSensors.forEach { add(JsonPrimitive(it)) } })
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
                put("actions", buildJsonArray { s.quickActions.forEach { add(QuickActions.toJson(it)) } })
                put("values", buildJsonArray { s.ownValues.forEach { add(OwnValues.toJson(it)) } })
            }.toString(),
        )
    }

    /** Starts a sign-in: the address of Home Assistant's login page, for this request only. */
    private fun signInJson(body: JsonObject, t: Texts): MiniHttp.Response {
        val url = body.string("url")?.let { HaUrls.normalizeBase(it) }
            ?: return refused(t[Strings.setupErrorAddress])
        val client = clientId() ?: return refused(t[Strings.setupErrorNotAllowed])
        val state = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(random.nextBytes(ByteArray(STATE_BYTES)))
        authorization = synchronized(lock) { Authorization(state, url, client, now(), setupsEnded) }
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
        val valid = same(state.encodeToByteArray(), pending.state.encodeToByteArray()) && now() - pending.at <= SIGN_IN_MS
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
        val signIn = SignedIn(pending.url, pending.clientId, refresh)
        // Kept for the save, and the sign-in it replaces is revoked. If its setup ended during
        // the exchange, nothing would save it: it is revoked at once.
        val (kept, dropped) = synchronized(lock) {
            if (pending.setup == setupsEnded) true to signedIn.also { signedIn = signIn } else false to signIn
        }
        if (dropped != null) revoke(dropped.url, dropped.refreshToken)
        return redirect(if (kept) SIGN_IN_OK else SIGN_IN_FAILED)
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
            ?: return refused(t[Strings.setupErrorAddress])
        val credentials = credentialsFor(url, body) ?: return refused(t[Strings.setupErrorCredentials])
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
                put("lights", statesJson(r.lights))
                put("actions", buildJsonArray {
                    r.actions.forEach { a ->
                        add(buildJsonObject {
                            put("id", a.entityId)
                            put("name", a.name)
                            if (a.fields.isNotEmpty()) put("fields", buildJsonArray { a.fields.forEach { add(fieldJson(it)) } })
                        })
                    }
                })
                put("sensors", statesJson(r.sensors))
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

    /** Lights or sensors as the page lists them, each with its state in words. */
    private fun statesJson(options: List<StateOption>) = buildJsonArray {
        options.forEach { o ->
            add(buildJsonObject {
                put("id", o.entityId)
                put("name", o.name)
                put("state", o.state)
            })
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
            ?: return refused(t[Strings.setupErrorAddress])
        val credentials = credentialsFor(url, body) ?: return refused(t[Strings.setupErrorCredentials])
        val ha = current.withHa(url, credentials)
        val withMa = maSettings(ha, body) ?: return refused(t[Strings.setupErrorMaAddress])
        if (withMa.maUrl != null && withMa.maToken == null) return refused(t[Strings.setupErrorMaToken])
        if (withMa.hasMusicAssistant && (withMa.maUrl != current.maUrl || withMa.maToken != current.maToken)) {
            when (checkMa(withMa.maUrl!!, withMa.maToken!!)) {
                MaLink.CONNECTED -> Unit
                MaLink.AUTH_FAILED -> return refused(t[Strings.setupErrorMaRejected])
                MaLink.UNREACHABLE -> return refused(t[Strings.setupErrorMaUnreachable])
            }
        }
        val entity = body.string("entity")?.trim()?.takeIf { EntityId.isValid(it) || it == EntityId.THIS_TV }
            ?: return refused(t[Strings.setupErrorSpeaker])
        // "This TV" follows the TV's own speaker, which needs Music Assistant, and turns playing here on.
        if (entity == EntityId.THIS_TV && !withMa.hasMusicAssistant) return refused(t[Strings.setupErrorThisTv])
        val tvSpeaker = entity == EntityId.THIS_TV || (body.boolean("tvSpeaker") ?: current.tvSpeaker)
        val minutes = body.int("sleepMinutes") ?: body.string("sleepMinutes")?.toIntOrNull()
            ?: current.sleepAfterMinutes
        if (minutes !in 1..Settings.MAX_SLEEP_MINUTES) return refused(t[Strings.setupErrorSleep])
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
            if (ids.any { it.isNotEmpty() && !EntityId.isLight(it) }) return refused(t[Strings.setupErrorLights])
            RoomLights(ids[0].ifEmpty { null }, ids[1].ifEmpty { null }, ids[2].ifEmpty { null })
        }
        // The quick settings' own buttons only when the page showed them; an empty list removes them.
        val actions = body.listOn("actions", current.quickActions, QuickActions.MAX) { QuickActions.from(it as? JsonObject) }
            ?: return refused(t[Strings.setupErrorActions])
        // The values in the status line, likewise.
        val values = body.listOn("values", current.ownValues, OwnValues.MAX) { OwnValues.from(it as? JsonObject) }
            ?: return refused(t[Strings.setupErrorValues])
        // The speakers in other rooms, likewise; the one chosen above is this room's.
        val otherRooms = (body.listOn("otherRooms", current.otherRooms) { it.entityIn("media_player") } ?: return refused(t[Strings.setupErrorOtherRooms]))
            .filter { it != entity }.distinct().take(Settings.MAX_OTHER_ROOMS)
        // The presence sensors, likewise: sensors that are on or off.
        val presence = (body.listOn("presence", current.presenceSensors) { it.entityIn("binary_sensor") } ?: return refused(t[Strings.setupErrorPresence]))
            .distinct().take(Settings.MAX_PRESENCE_SENSORS)
        val updated = withMa.following(entity).withPlayHere(tvSpeaker).copy(
            sleepAfterMinutes = minutes,
            sleepAction = action,
            showClock = body.boolean("clock") ?: current.showClock,
            remoteControl = body.boolean("remote") ?: current.remoteControl,
            roomLights = roomLights,
            quickActions = actions,
            ownValues = values,
            otherRooms = otherRooms,
            presenceSensors = presence,
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
            put("message", t[Strings.setupSaved])
        }.toString())
    }

    /** [s] with Music Assistant's address and token from the form; null for an address that is none. */
    private fun maSettings(s: Settings, body: JsonObject): Settings? {
        val raw = body.string("maUrl")?.trim().orEmpty()
        if (raw.isEmpty()) return s.withMa(null, null)
        val url = HaUrls.normalizeBase(raw) ?: return null
        return s.withMa(url, body.string("maToken")?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The list the page sent under [key], each entry as [read] takes it, the first [limit] only;
     * [kept] when the page sent none, and null when it is no list or [read] refuses an entry.
     */
    private inline fun <T> JsonObject.listOn(key: String, kept: List<T>, limit: Int = Int.MAX_VALUE, read: (JsonElement) -> T?): List<T>? =
        when (val list = this[key]) {
            null -> kept
            is JsonArray -> list.take(limit).map { read(it) ?: return null }
            else -> null
        }

    /** This entry as the id of an entity in [domain]; null when it is none. */
    private fun JsonElement.entityIn(domain: String): String? = string()?.takeIf { EntityId.isIn(domain, it) }

    private fun redirect(location: String) = MiniHttp.Response(303, "text/plain; charset=utf-8", ByteArray(0), mapOf("Location" to location))

    /** The page's request refused, saying why in [message]. */
    private fun refused(message: String) = MiniHttp.Response.json(400, error(message))

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("message", message)
    }.toString()

    private fun enc(s: String) = formEncode(s)

    /** Whether [a] and [b] are the same, in a time that doesn't tell where they differ. */
    private fun same(a: ByteArray, b: ByteArray): Boolean {
        if (b.isEmpty()) return a.isEmpty()
        var differ = a.size - b.size
        for (i in a.indices) differ = differ or (a[i].toInt() xor b[if (i < b.size) i else 0].toInt())
        return differ == 0
    }

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
