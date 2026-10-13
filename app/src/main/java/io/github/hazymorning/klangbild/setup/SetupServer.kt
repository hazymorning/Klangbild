package io.github.hazymorning.klangbild.setup

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.config.ActionKind
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.data.ha.HaAuth
import io.github.hazymorning.klangbild.data.ha.HaAuthRejectedException
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaRest
import io.github.hazymorning.klangbild.data.ha.LocalNetwork
import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** What the TV shows while setup is open. */
data class SetupInfo(
    /** Address to type in, e.g. `http://192.168.1.23:8765`; null without network. */
    val url: String?,
    val code: String,
    /** QR modules (true = dark), square. Encodes [url] with the code. */
    val qr: Array<BooleanArray>?,
)

/**
 * Serves the setup page on the local network while the setup screen is visible. Plain sockets
 * (no web framework): one short request per connection, at most [MAX_CONNECTIONS] at a time,
 * each with [REQUEST_DEADLINE_MS] to arrive and be answered. Requests must name the page's own
 * address as their host (no other site can rebind its name to the TV), a call to the API from
 * another origin is refused, and so is one that is not JSON (a form on another page cannot send
 * it unasked). Without an address of its own (no home network) nothing is served. A request that
 * has arrived is answered even when serving stops meanwhile: saving ends the setup, and its
 * answer must still reach the phone. Once [stop] has returned, the port takes no connection.
 */
class SetupServer(
    private val scope: CoroutineScope,
    private val handler: SetupHandler,
    private val addressProvider: () -> InetAddress? = ::guessLanAddress,
) {
    private val _info = MutableStateFlow<SetupInfo?>(null)
    val info: StateFlow<SetupInfo?> = _info.asStateFlow()

    /** The page's address while it is served (`http://192.168.1.23:8765`). */
    val origin: String? get() = _info.value?.url

    private var socket: ServerSocket? = null

    /** The thread taking the connections: the port is shut only once it has let go of it. */
    private var acceptor: Thread? = null

    /** Connections whose request is still arriving: a stop closes them at once. */
    private val arriving = HashSet<Socket>()

    /**
     * Serves the page, with a new code. Without an address of its own there is no page to serve:
     * the TV says there is no network, and the next call tries again.
     */
    @Synchronized
    fun start() {
        if (socket != null) return
        val address = addressProvider()
        val server = address?.let(::openSocket)
        if (address == null || server == null) {
            _info.value = SetupInfo(null, handler.code, null)
            return
        }
        val own = "http://${address.hostAddress}:${server.localPort}"
        socket = server
        handler.rotateCode()
        handler.onCodeChanged = { publish(own) }
        publish(own)
        val slots = Semaphore(MAX_CONNECTIONS)
        acceptor = thread(name = "Klangbild setup", isDaemon = true) {
            while (true) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                // One that came in as serving stopped is not served; too many at once, the newest waits for nothing.
                val taken = synchronized(arriving) { !server.isClosed && slots.tryAcquire() && arriving.add(client) }
                if (!taken) {
                    runCatching { client.close() }
                    if (server.isClosed) break else continue
                }
                // Not tied to this loop: an answer under way outlives a stop.
                scope.launch(Dispatchers.IO) {
                    try {
                        serve(client, own)
                    } finally {
                        slots.release()
                    }
                }
            }
        }
    }

    /**
     * Stops serving: the listening socket closes, and so does every connection whose request is
     * still arriving. A request that has arrived is still answered (within its deadline), and a
     * sign-in never saved is revoked. A new code no longer reaches the screen.
     */
    @Synchronized
    fun stop() {
        val wasServing = socket != null
        handler.onCodeChanged = {}
        runCatching { socket?.close() }
        socket = null
        // The system still takes connections on a closed socket while a thread is blocked in its
        // accept(): the port is shut only once that thread has let go of it, which it does at once.
        acceptor?.join(STOP_WAIT_MS)
        acceptor = null
        synchronized(arriving) {
            arriving.forEach { runCatching { it.close() } }
            arriving.clear()
        }
        _info.value = null
        if (wasServing) scope.launch { handler.reset() }
    }

    private fun publish(url: String) {
        _info.value = SetupInfo(url, handler.code, qrMatrix("$url/?code=${handler.code}"))
    }

    /** The listening socket, on the LAN address only: the first of [PORTS] that is free, else any. */
    private fun openSocket(address: InetAddress): ServerSocket? {
        for (port in PORTS) {
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(address, port), BACKLOG)
                return candidate
            } catch (_: IOException) {
                // Port taken: try the next one.
                runCatching { candidate.close() }
            }
        }
        return runCatching { ServerSocket(0, BACKLOG, address) }.getOrNull()
    }

    /** Answers [client], which came to the page at [own] (also when serving stops meanwhile). */
    private suspend fun serve(client: Socket, own: String) {
        // However slowly a request arrives and is answered, it has this long; then its socket closes under it.
        val deadline = scope.launch {
            delay(REQUEST_DEADLINE_MS)
            runCatching { client.close() }
        }
        try {
            client.use { s ->
                val texts = handler.texts()
                val response = try {
                    s.soTimeout = READ_TIMEOUT_MS
                    val req = MiniHttp.read(s.getInputStream())
                    // It has arrived: from now on a stop lets it be answered (one cut off just now is not).
                    if (!synchronized(arriving) { arriving.remove(client) }) return
                    refusal(req, own, texts) ?: handler.handle(req)
                } catch (_: MiniHttp.BadRequest) {
                    MiniHttp.Response.text(400, texts[R.string.setup_error_request])
                } catch (_: IOException) {
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    MiniHttp.Response.json(500, buildJsonObject {
                        put("ok", false)
                        put("message", texts[R.string.setup_error_internal])
                    }.toString())
                }
                runCatching { MiniHttp.write(s.getOutputStream(), response) }
            }
        } finally {
            deadline.cancel()
            synchronized(arriving) { arriving -= client }
        }
    }

    /**
     * Why [req] is refused, or null: it must be for the page's own address [own]; a call to the
     * API must come from the page itself and be JSON.
     */
    private fun refusal(req: MiniHttp.Request, own: String, t: Texts): MiniHttp.Response? {
        val forbidden = MiniHttp.Response.text(403, t[R.string.setup_error_not_allowed])
        if (req.headers["host"] != own.removePrefix("http://")) return forbidden
        if (req.method == "POST") {
            val from = req.headers["origin"]
            if (from != null && from != own) return forbidden
            if (req.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") return forbidden
        }
        return null
    }

    companion object {
        val PORTS = listOf(8765, 8766, 8767, 8768, 8769)

        /** Connections served at once; a phone filling in one form needs one or two. */
        const val MAX_CONNECTIONS = 4

        /** Connections waiting to be accepted. */
        private const val BACKLOG = 8

        /** One read may wait this long. */
        const val READ_TIMEOUT_MS = 5_000

        /** The whole request may take this long. */
        const val REQUEST_DEADLINE_MS = 15_000L

        /** How long a stop waits for the thread taking the connections to let go of the port. */
        private const val STOP_WAIT_MS = 2_000L

        fun qrMatrix(text: String): Array<BooleanArray> {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
        }

        /** First private IPv4 address of an up, non-loopback interface (Ethernet or Wi-Fi). */
        fun guessLanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        /**
         * Asks Home Assistant at [url] for its media players, lights, weather entities, what a
         * button can work and every entity for the values and conditions (REST `/api/states`),
         * and for the fields its scripts declare (`/api/services`, as far as it answers), with
         * [credentials]. Every failure becomes one calm sentence for the setup page.
         */
        suspend fun discover(http: OkHttpClient, url: String, credentials: HaCredentials, t: Texts): DiscoverResult {
            val rest = HaRest(http, HaAuth(http, url, credentials))
            return try {
                coroutineScope {
                    // A script's fields help, but the page works without them.
                    val services = async {
                        try {
                            rest.services()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    parseSpeakers(rest.allStates(), t, services.await())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: HaAuthRejectedException) {
                DiscoverResult.Failed(t[R.string.discover_rejected])
            } catch (e: IOException) {
                val code = Regex("""answered (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                when {
                    code != null -> DiscoverResult.Failed(t.format(R.string.discover_unexpected, code))
                    e is LocalNetwork.CleartextRefused -> DiscoverResult.Failed(t[R.string.discover_cleartext])
                    else -> DiscoverResult.Failed(t.format(R.string.discover_unreachable, url))
                }
            } catch (_: IllegalArgumentException) {
                DiscoverResult.Failed(t[R.string.discover_invalid_address])
            }
        }

        /** The lists for the setup page from all states as JSON text, and the scripts' fields from all [services]. */
        fun parseSpeakers(body: String, t: Texts, services: String? = null): DiscoverResult {
            val arr = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                ?: return DiscoverResult.Failed(t[R.string.discover_garbled])
            val states = arr.mapNotNull { it as? JsonObject }
            val fields = services?.let(::scriptFields).orEmpty()
            fun JsonObject.id() = (this["entity_id"] as? JsonPrimitive)?.content
            fun JsonObject.attrs() = this["attributes"] as? JsonObject
            fun JsonObject.name() = (attrs()?.get("friendly_name") as? JsonPrimitive)?.content ?: id()
            fun JsonObject.state() = (this["state"] as? JsonPrimitive)?.content ?: "unknown"
            val speakers = states.mapNotNull { o ->
                val id = o.id()?.takeIf { it.startsWith("media_player.") } ?: return@mapNotNull null
                val attrs = o.attrs()
                val ma = attrs?.get("mass_player_type") != null ||
                    (attrs?.get("app_id") as? JsonPrimitive)?.content == "music_assistant"
                // A screen of Music Assistant's own (a TV it plays to) is no speaker for this one.
                val display = (attrs?.get("device_class") as? JsonPrimitive)?.content == "tv" && ma
                if (display) null else SpeakerOption(id, o.name() ?: id, stateWord(o.state(), t), ma)
            }.sortedWith(compareByDescending<SpeakerOption> { it.musicAssistant }.thenBy { it.name.lowercase() })
            // Every light Home Assistant controls can join in: in color, in white, or by its brightness.
            val lights = states.mapNotNull { o ->
                val id = o.id()?.takeIf { it.startsWith("light.") } ?: return@mapNotNull null
                LightOption(id, o.name() ?: id, stateWord(o.state(), t))
            }.sortedBy { it.name.lowercase() }
            // Weather entities: their clouds set the landscape's sky.
            val weather = states.mapNotNull { o ->
                val id = o.id()?.takeIf { it.startsWith("weather.") } ?: return@mapNotNull null
                WeatherOption(id, o.name() ?: id, weatherWord(o.state(), t))
            }.sortedBy { it.name.lowercase() }
            // What the quick settings' own buttons can work, by kind (scripts first), then by name;
            // a script with the fields it declares.
            val actions = states.mapNotNull { o ->
                val id = o.id()?.takeIf { ActionKind.of(it) != null } ?: return@mapNotNull null
                ActionOption(id, o.name() ?: id, if (id.startsWith("script.")) fields[id.substringAfter('.')].orEmpty() else emptyList())
            }.sortedWith(compareBy<ActionOption> { ActionKind.DOMAINS.indexOf(it.entityId.substringBefore('.')) }.thenBy { it.name.lowercase() })
            // Every entity, for the values in the status line and the buttons' conditions.
            val entities = states.mapNotNull { o ->
                val id = o.id() ?: return@mapNotNull null
                entityOption(id, o.name() ?: id, o.state(), o.attrs())
            }.sortedBy { it.name.lowercase() }
            return DiscoverResult.Ok(speakers, lights, weather, actions, entities)
        }

        /**
         * An entity as the page offers it: its raw state and unit, and the attributes worth
         * showing or comparing (words and numbers cut short, lists by their count). What only
         * describes the entity (its name, icon, unit, what it supports) and long lists of
         * choices are left out.
         */
        private fun entityOption(id: String, name: String, state: String, attrs: JsonObject?): EntityOption {
            val words = LinkedHashMap<String, String>()
            val lists = LinkedHashMap<String, Int>()
            attrs?.forEach { (key, v) ->
                if (key in DESCRIBING || key.endsWith("_list") || words.size + lists.size >= MAX_ATTRIBUTES) return@forEach
                when (v) {
                    is JsonPrimitive -> if (v !is JsonNull) words[key] = v.content.take(MAX_ATTRIBUTE_TEXT)
                    is JsonArray -> lists[key] = v.size
                    else -> Unit
                }
            }
            val unit = attrs?.string("unit_of_measurement")
            return EntityOption(id, name, state.take(MAX_ATTRIBUTE_TEXT), unit, words, lists)
        }

        /** Attributes that describe an entity rather than tell how it stands. */
        private val DESCRIBING = setOf(
            "friendly_name", "icon", "entity_picture", "unit_of_measurement", "device_class", "state_class", "supported_features",
            "attribution", "restored", "editable", "id", "assumed_state", "supported_color_modes", "options", "entity_picture_local",
        )

        /** At most this many attributes per entity, each cut to this many characters. */
        private const val MAX_ATTRIBUTES = 24
        private const val MAX_ATTRIBUTE_TEXT = 40

        /**
         * The fields each script declares, from all of Home Assistant's actions as JSON text
         * (`script.<id>` is the action `script.<id>`): what the page asks for. A field's
         * selector says how: a switch, a number, a choice, an entity, or text.
         */
        fun scriptFields(services: String): Map<String, List<ScriptField>> {
            val all = runCatching { Json.parseToJsonElement(services) as? JsonArray }.getOrNull() ?: return emptyMap()
            val script = all.firstOrNull { ((it as? JsonObject)?.get("domain") as? JsonPrimitive)?.content == "script" } as? JsonObject
            val scripts = script?.get("services") as? JsonObject ?: return emptyMap()
            return scripts.mapValues { (_, desc) ->
                val declared = (desc as? JsonObject)?.get("fields") as? JsonObject ?: return@mapValues emptyList()
                // Fields may stand in sections of their own.
                val flat = declared.entries.flatMap { (key, f) ->
                    val inner = (f as? JsonObject)?.get("fields") as? JsonObject
                    inner?.entries?.map { it.key to it.value } ?: listOf(key to f)
                }
                flat.mapNotNull { (key, f) -> (f as? JsonObject)?.let { scriptField(key, it) } }
            }
        }

        private fun scriptField(key: String, f: JsonObject): ScriptField? {
            if (!QuickActions.isVariableName(key)) return null
            val selector = f["selector"] as? JsonObject
            val (kind, config) = selector?.entries?.firstOrNull()?.let { it.key to (it.value as? JsonObject) } ?: ("text" to null)
            val multiple = (config?.get("multiple") as? JsonPrimitive)?.content == "true"
            val type = when {
                multiple -> ScriptField.TEXT
                kind == "boolean" -> ScriptField.BOOLEAN
                kind == "number" -> ScriptField.NUMBER
                kind == "select" -> ScriptField.SELECT
                kind == "entity" -> ScriptField.ENTITY
                else -> ScriptField.TEXT
            }
            val options = (config?.get("options") as? JsonArray).orEmpty().mapNotNull { o ->
                when (o) {
                    is JsonPrimitive -> o.content to o.content
                    is JsonObject -> o.string("value")?.let { v -> v to (o.string("label") ?: v) }
                    else -> null
                }
            }
            // An entity selector names its domains directly, or in its filters.
            fun domains(o: JsonObject?): List<String> = when (val d = o?.get("domain")) {
                is JsonPrimitive -> listOf(d.content)
                is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
            val filters = when (val fl = config?.get("filter")) {
                is JsonObject -> listOf(fl)
                is JsonArray -> fl.mapNotNull { it as? JsonObject }
                else -> emptyList()
            }
            return ScriptField(
                key = key,
                name = f.string("name") ?: key,
                description = f.string("description"),
                required = (f["required"] as? JsonPrimitive)?.content == "true",
                type = if (type == ScriptField.SELECT && options.isEmpty()) ScriptField.TEXT else type,
                options = options,
                default = f["default"],
                example = (f["example"] as? JsonPrimitive)?.content,
                domains = (domains(config) + filters.flatMap(::domains)).distinct(),
            )
        }

        /** A weather condition (https://developers.home-assistant.io/docs/core/entity/weather) in words. */
        private fun weatherWord(state: String, t: Texts) = when (state) {
            "sunny" -> t[R.string.weather_sunny]
            "clear-night" -> t[R.string.weather_clear_night]
            "partlycloudy" -> t[R.string.weather_partlycloudy]
            "cloudy" -> t[R.string.weather_cloudy]
            "fog" -> t[R.string.weather_fog]
            "rainy", "pouring" -> t[R.string.weather_rain]
            "snowy", "snowy-rainy" -> t[R.string.weather_snow]
            "hail" -> t[R.string.weather_hail]
            "lightning", "lightning-rainy" -> t[R.string.weather_thunderstorm]
            "windy", "windy-variant" -> t[R.string.weather_windy]
            "exceptional" -> t[R.string.weather_exceptional]
            else -> stateWord(state, t)
        }

        private fun stateWord(state: String, t: Texts) = when (state) {
            "playing" -> t[R.string.entity_state_playing]
            "paused" -> t[R.string.entity_state_paused]
            "idle" -> t[R.string.entity_state_idle]
            "off" -> t[R.string.entity_state_off]
            "on" -> t[R.string.entity_state_on]
            "unavailable" -> t[R.string.entity_state_unavailable]
            "buffering" -> t[R.string.entity_state_buffering]
            "standby" -> t[R.string.entity_state_standby]
            else -> t[R.string.entity_state_unknown]
        }
    }
}
