package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.json.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Where and how to reach Home Assistant. */
data class HaConfig(val auth: HaAuth) {
    val baseUrl: String get() = auth.baseUrl
}

/** Connection state as the rest of the app sees it, never raw exceptions. */
sealed interface HaStatus {
    data object Stopped : HaStatus
    data class Connecting(val attempt: Int) : HaStatus
    data object Connected : HaStatus

    /** The token was rejected. No automatic retries until the config changes (or [HaConnection.retryNow]). */
    data class AuthFailed(val message: String?) : HaStatus

    /** Lost or failed; the next attempt happens in [retryInMs]. */
    data class Waiting(val attempt: Int, val retryInMs: Long, val reason: String?) : HaStatus
}

/** A Home Assistant error answer to a command. */
class HaCommandException(val code: String, message: String) : Exception(message)

/**
 * Home Assistant took a command but has not answered within [timeoutMs]. It answers a call only
 * when the call is done, so a long automation is still running.
 */
class HaNoAnswerException(timeoutMs: Long) : IOException("no answer within $timeoutMs ms")

/**
 * An entity as the entity registry lists it for display (`config/entity_registry/list_for_display`);
 * a sensor with the number of decimals Home Assistant shows it with ([displayPrecision]).
 */
data class RegistryEntry(
    val entityId: String,
    val platform: String,
    val deviceId: String?,
    val translationKey: String?,
    val displayPrecision: Int? = null,
)

/**
 * The WebSocket connection to Home Assistant, using only its documented commands
 * (https://developers.home-assistant.io/docs/api/websocket). It authenticates, follows the
 * given entities, keeps the connection alive with pings and reconnects with exponential
 * backoff. State is exposed as [StateFlow]s.
 *
 * Entities are followed with `subscribe_trigger` (a state trigger on just those entities, which
 * fires on attribute changes too). That command needs an administrator; for other users the
 * connection falls back to `subscribe_events` for `state_changed` and keeps only the followed
 * entities. A new subscription starts from each entity's state as the REST API gives it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HaConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long = 30_000,
    private val pongTimeoutMs: Long = 10_000,
    private val handshakeTimeoutMs: Long = 15_000,
    private val backoffFactory: () -> Backoff = { Backoff() },
    /** How long to wait before asking the REST API again for a state it could not give. */
    private val restRetryMs: List<Long> = listOf(2_000L, 5_000L, 15_000L),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<HaStatus>(HaStatus.Stopped)
    val status: StateFlow<HaStatus> = _status.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val entities: StateFlow<Map<String, EntityState>> = _entities.asStateFlow()

    private val _missing = MutableStateFlow<Set<String>>(emptySet())

    /** Followed entities that Home Assistant says do not exist. */
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** Local wall-clock time minus server time, estimated from state changes. */
    val clockSkew = ClockSkew()

    private var loop: Job? = null
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val extraIds = MutableStateFlow<Set<String>>(emptySet())

    private val _config = MutableStateFlow<JsonObject?>(null)

    /** Home Assistant's own configuration (`get_config`: its location, time zone and so on), once a session has asked for it. */
    val config: StateFlow<JsonObject?> = _config.asStateFlow()

    private val _registry = MutableStateFlow<List<RegistryEntry>?>(null)

    /** The entity registry as listed for display, once a session has asked for it. */
    val registry: StateFlow<List<RegistryEntry>?> = _registry.asStateFlow()

    @Volatile private var session: Session? = null

    fun start(config: HaConfig, entityIds: List<String>) {
        stop()
        loop = scope.launch { runLoop(config, entityIds) }
    }

    /**
     * Also follows these entities (the other speakers of a group, the lights). Their states
     * appear in [entities] next to the main ones; the set is followed again after a reconnect.
     */
    fun watch(ids: Set<String>) {
        extraIds.value = ids
    }

    fun stop() {
        loop?.cancel()
        loop = null
        session?.socket?.cancel()
        session = null
        _status.value = HaStatus.Stopped
        _entities.value = emptyMap()
        _missing.value = emptySet()
    }

    /** Skip the remaining backoff (the network just came back, say). */
    fun retryNow() {
        wakeUp.trySend(Unit)
    }

    /**
     * Calls a service. With [returnResponse] the `response` object is returned.
     * Throws [HaCommandException] on Home Assistant's errors, IllegalStateException when offline.
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        returnResponse: Boolean = false,
        data: JsonObject? = null,
        timeoutMs: Long = 10_000,
    ): JsonObject? = (call(domain, service, entityId, returnResponse, data, timeoutMs) as? JsonObject)?.get("response") as? JsonObject

    /**
     * Calls a service and returns the id of the context Home Assistant gave the call: the
     * states it changes carry it, so they can be told from changes made by anyone else.
     */
    suspend fun callServiceForContext(domain: String, service: String, entityId: String?, data: JsonObject? = null, timeoutMs: Long = 10_000): String? =
        (((call(domain, service, entityId, false, data, timeoutMs) as? JsonObject)?.get("context") as? JsonObject)?.get("id") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    private suspend fun call(domain: String, service: String, entityId: String?, returnResponse: Boolean, data: JsonObject?, timeoutMs: Long): JsonElement? {
        val s = session ?: error("not connected")
        return s.command(timeoutMs) { id ->
            buildJsonObject {
                put("id", id)
                put("type", "call_service")
                put("domain", domain)
                put("service", service)
                if (entityId != null) put("target", buildJsonObject { put("entity_id", entityId) })
                if (data != null) put("service_data", data)
                if (returnResponse) put("return_response", true)
            }
        }
    }

    private suspend fun runLoop(config: HaConfig, entityIds: List<String>) {
        val backoff = backoffFactory()
        // A refused access token from a sign-in is replaced by a fresh one once before giving up.
        var freshTokenTried = false
        while (scope.isActive) {
            _status.value = HaStatus.Connecting(backoff.attempt)
            val end = try {
                runSession(config, entityIds) {
                    backoff.reset()
                    freshTokenTried = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HaAuthRejectedException) {
                SessionEnd.AuthInvalid(e.message)
            } catch (e: Exception) {
                SessionEnd.Lost(e.message ?: e.javaClass.simpleName)
            } finally {
                session = null
            }
            if (end is SessionEnd.TokenRefused && !freshTokenTried && config.auth.refreshable) {
                freshTokenTried = true
                config.auth.invalidate(end.token)
                continue
            }
            when (end) {
                is SessionEnd.AuthInvalid, is SessionEnd.TokenRefused -> {
                    _status.value = HaStatus.AuthFailed((end as? SessionEnd.AuthInvalid)?.message ?: (end as SessionEnd.TokenRefused).message)
                    _entities.value = emptyMap()
                    // Wait for an explicit retry; a wrong token will not fix itself.
                    wakeUp.receive()
                    freshTokenTried = false
                }
                is SessionEnd.Lost -> {
                    val delayMs = backoff.nextDelayMs()
                    _status.value = HaStatus.Waiting(backoff.attempt, delayMs, end.reason)
                    select<Unit> {
                        wakeUp.onReceive { }
                        onTimeout(delayMs) { }
                    }
                }
            }
        }
    }

    private sealed interface SessionEnd {
        data class AuthInvalid(val message: String?) : SessionEnd

        /** Home Assistant refused [token] at the WebSocket's login. */
        data class TokenRefused(val token: String, val message: String?) : SessionEnd
        data class Lost(val reason: String?) : SessionEnd
    }

    private sealed interface WsEvent {
        data class Text(val text: String) : WsEvent
        data class Closed(val code: Int, val reason: String) : WsEvent
        data class Failure(val error: Throwable) : WsEvent
    }

    /** States the REST API gave for [requested] ids: found, or known not to exist. */
    private class RestStates(val found: List<EntityState>, val missing: Set<String>)

    /** An answer, which may itself be null. */
    private class Answer(val value: JsonElement?)

    private inner class Session(val socket: WebSocket) {
        private val nextId = AtomicInteger(1)
        val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
        @Volatile var lastPong = 0L

        fun send(obj: JsonObject): Boolean = socket.send(obj.toString())

        /**
         * Sends a command and waits for its answer. No answer in time, or a connection that
         * ends under the call, is the call's failure ([IOException]), never a cancellation that
         * would silently end the caller's coroutine; only the caller's own cancellation passes.
         */
        suspend fun command(timeoutMs: Long, build: (Int) -> JsonObject): JsonElement? {
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<JsonElement?>()
            pending[id] = deferred
            try {
                if (!send(build(id))) throw IOException("socket closed")
                val answer = withTimeoutOrNull(timeoutMs) { Answer(deferred.await()) }
                    ?: throw HaNoAnswerException(timeoutMs)
                return answer.value
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("connection lost", e)
            } finally {
                pending.remove(id)
            }
        }

        fun nextId(): Int = nextId.getAndIncrement()
    }

    /** How the followed entities' changes arrive. */
    private enum class Feed {
        /** `subscribe_trigger` with a state trigger: only the followed entities (administrators). */
        TRIGGER,

        /** `subscribe_events` for `state_changed`: every change, the followed ones kept. */
        EVENTS,
    }

    private suspend fun runSession(config: HaConfig, entityIds: List<String>, onSubscribed: () -> Unit): SessionEnd = coroutineScope {
        val token = config.auth.accessToken()
        val rest = HaRest(http, config.auth)
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url(HaUrls.webSocketUrl(config.baseUrl)).build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(WsEvent.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                events.trySend(WsEvent.Closed(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WsEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WsEvent.Failure(t))
            }
        })
        val s = Session(socket)
        try {
            // --- handshake -------------------------------------------------------------
            val first = withTimeoutOrNull(handshakeTimeoutMs) { events.receive() }
                ?: return@coroutineScope SessionEnd.Lost("no auth_required")
            if (first is WsEvent.Failure || first is WsEvent.Closed) return@coroutineScope lost(first)
            val firstMsg = (first as WsEvent.Text).let { parseMessages(it.text) }.firstOrNull()
            if (firstMsg?.type() != "auth_required") return@coroutineScope SessionEnd.Lost("unexpected greeting")
            s.send(buildJsonObject {
                put("type", "auth")
                put("access_token", token)
            })
            val authAnswer = withTimeoutOrNull(handshakeTimeoutMs) { events.receive() }
                ?: return@coroutineScope SessionEnd.Lost("auth timeout")
            if (authAnswer !is WsEvent.Text) return@coroutineScope lost(authAnswer)
            val auth = parseMessages(authAnswer.text).firstOrNull()
            when (auth?.type()) {
                "auth_ok" -> Unit
                "auth_invalid" -> return@coroutineScope SessionEnd.TokenRefused(token, auth.string("message"))
                else -> return@coroutineScope SessionEnd.Lost("unexpected auth answer")
            }
            session = s
            s.lastPong = System.currentTimeMillis()

            // --- subscribe ---------------------------------------------------------------
            val store = EntityStore()
            val main = entityIds.toSet()
            var feed = Feed.TRIGGER
            var mainSub = s.nextId()
            s.send(subscribeTrigger(mainSub, entityIds))
            var subscribed = false
            var extraSub: Int? = null
            var extras: Set<String> = emptySet()
            val restResults = Channel<RestStates>(Channel.UNLIMITED)
            val extraChanges = Channel<Set<String>>(Channel.CONFLATED)
            fun followed() = main + extras
            fun publish() {
                _entities.value = store.snapshot()
            }

            /** Asks the REST API for [ids]' states; what it cannot give is asked again a few times. */
            fun fetch(ids: Set<String>) {
                if (ids.isEmpty()) return
                launch {
                    var open = ids
                    for (wait in listOf(0L) + restRetryMs) {
                        delay(wait)
                        val found = ArrayList<EntityState>()
                        val missing = HashSet<String>()
                        val failed = HashSet<String>()
                        for (id in open) {
                            when (val r = rest.state(id)) {
                                is HaRest.Fetched.Found -> found += r.state
                                HaRest.Fetched.Missing -> missing += id
                                HaRest.Fetched.Failed -> failed += id
                            }
                        }
                        restResults.send(RestStates(found, missing))
                        if (failed.isEmpty()) break
                        open = failed
                    }
                }
            }

            fun followExtras(ids: Set<String>) {
                val gone = extras - ids - main
                val added = ids - extras - main
                extras = ids
                if (feed == Feed.TRIGGER) {
                    extraSub?.let { old -> s.send(unsubscribe(s.nextId(), old)) }
                    extraSub = null
                    val own = ids - main
                    if (own.isNotEmpty()) {
                        val id = s.nextId()
                        s.send(subscribeTrigger(id, own.toList()))
                        extraSub = id
                    }
                }
                if (gone.isNotEmpty()) {
                    store.retain(followed())
                    _missing.value = _missing.value - gone
                    publish()
                }
                fetch(added)
            }

            // Where Home Assistant stands (for the landscape's sun and moon), and the entity
            // registry (the favorite button of a Music Assistant speaker): asked once, on the side.
            launch {
                (optional(s) { id -> buildJsonObject { put("id", id); put("type", "get_config") } } as? JsonObject)?.let { _config.value = it }
            }
            launch {
                val list = optional(s) { id -> buildJsonObject { put("id", id); put("type", "config/entity_registry/list_for_display") } }
                registryOf(list)?.let { _registry.value = it }
            }

            val pinger = launch {
                while (isActive) {
                    delay(pingIntervalMs)
                    val id = s.nextId()
                    val sentAt = System.currentTimeMillis()
                    s.send(buildJsonObject {
                        put("id", id)
                        put("type", "ping")
                    })
                    delay(pongTimeoutMs)
                    if (s.lastPong < sentAt) {
                        socket.cancel() // dead connection: triggers onFailure, then a reconnect
                        break
                    }
                }
            }
            val extraWatcher = launch { extraIds.collect { extraChanges.send(it) } }

            // --- message loop ------------------------------------------------------------
            try {
                while (true) {
                    val next = select<Any> {
                        events.onReceive { it }
                        restResults.onReceive { it }
                        if (subscribed) extraChanges.onReceive { it }
                    }
                    if (next is Set<*>) {
                        val ids = next.filterIsInstance<String>().toSet()
                        if (ids != extras) followExtras(ids)
                        continue
                    }
                    if (next is RestStates) {
                        var changed = false
                        for (st in next.found) if (st.entityId in followed()) changed = store.put(st) or changed
                        val missing = next.missing.intersect(followed())
                        if (missing.isNotEmpty()) {
                            for (id in missing) changed = store.remove(id) or changed
                            _missing.value = _missing.value + missing
                        }
                        val known = next.found.map { it.entityId }.toSet()
                        if (known.any { it in _missing.value }) _missing.value = _missing.value - known
                        if (changed || missing.isNotEmpty()) publish()
                        continue
                    }
                    when (val ev = next as WsEvent) {
                        is WsEvent.Text -> {
                            // Every change in the house arrives with the fallback feed: only the
                            // followed entities' are worth parsing.
                            if (feed == Feed.EVENTS && subscribed && followed().none { ev.text.contains("\"$it\"") }) {
                                if (!ev.text.contains("\"type\":\"result\"") && !ev.text.contains("\"type\":\"pong\"")) continue
                            }
                            for (msg in parseMessages(ev.text)) {
                                val id = (msg["id"] as? JsonPrimitive)?.intOrNull
                                when (msg.type()) {
                                    "event" -> {
                                        if (id == null || (id != mainSub && id != extraSub)) continue
                                        val event = msg["event"] as? JsonObject ?: continue
                                        val (entity, state) = changeOf(event, feed) ?: continue
                                        if (entity !in followed()) continue
                                        val changed = if (state == null) store.remove(entity) else store.put(state)
                                        if (state != null) {
                                            clockSkew.addSample(System.currentTimeMillis() - (state.lastUpdated * 1000).toLong())
                                            if (entity in _missing.value) _missing.value = _missing.value - entity
                                        }
                                        if (changed) publish()
                                    }
                                    "result" -> {
                                        val ok = (msg["success"] as? JsonPrimitive)?.booleanOrNull == true
                                        val error = msg["error"] as? JsonObject
                                        val waiter = id?.let { s.pending[it] }
                                        when {
                                            waiter != null -> if (ok) {
                                                waiter.complete(msg["result"])
                                            } else {
                                                waiter.completeExceptionally(
                                                    HaCommandException(error?.string("code") ?: "unknown", error?.string("message") ?: "error"),
                                                )
                                            }
                                            id == mainSub && ok -> {
                                                subscribed = true
                                                _status.value = HaStatus.Connected
                                                onSubscribed()
                                                fetch(main)
                                            }
                                            // Not an administrator: every state change, the followed ones kept.
                                            id == mainSub && feed == Feed.TRIGGER && error?.string("code") == "unauthorized" -> {
                                                feed = Feed.EVENTS
                                                mainSub = s.nextId()
                                                s.send(buildJsonObject {
                                                    put("id", mainSub)
                                                    put("type", "subscribe_events")
                                                    put("event_type", "state_changed")
                                                })
                                            }
                                            id == mainSub -> return@coroutineScope SessionEnd.Lost("subscribe failed: ${error?.string("code")}")
                                            id != null && id == extraSub && !ok -> extraSub = null
                                        }
                                    }
                                    "pong" -> s.lastPong = System.currentTimeMillis()
                                }
                            }
                        }
                        is WsEvent.Closed, is WsEvent.Failure -> return@coroutineScope lost(ev)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                SessionEnd.Lost(null)
            } finally {
                pinger.cancel()
                extraWatcher.cancel()
                s.pending.values.forEach { it.cancel() }
            }
        } finally {
            socket.cancel()
        }
    }

    /** A command whose answer is welcome but not needed: null when it fails. */
    private suspend fun optional(s: Session, build: (Int) -> JsonObject): JsonElement? = try {
        s.command(handshakeTimeoutMs, build)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The entity and its new state (null: removed) from a trigger or a `state_changed` event. */
    private fun changeOf(event: JsonObject, feed: Feed): Pair<String, EntityState?>? {
        val (entity, state) = when (feed) {
            Feed.TRIGGER -> ((event["variables"] as? JsonObject)?.get("trigger") as? JsonObject)?.let { it.string("entity_id") to it["to_state"] }
            Feed.EVENTS -> (event["data"] as? JsonObject)?.let { it.string("entity_id") to it["new_state"] }
        } ?: return null
        return (entity ?: return null) to EntityState.of(state as? JsonObject)
    }

    private fun registryOf(list: JsonElement?): List<RegistryEntry>? {
        val entities = ((list as? JsonObject)?.get("entities") as? JsonArray) ?: return null
        return entities.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RegistryEntry(
                entityId = o.string("ei") ?: return@mapNotNull null,
                platform = o.string("pl") ?: return@mapNotNull null,
                deviceId = o.string("di"),
                translationKey = o.string("tk"),
                // Home Assistant offers up to six decimals.
                displayPrecision = (o["dp"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..6 },
            )
        }
    }

    private fun subscribeTrigger(id: Int, entityIds: List<String>) = buildJsonObject {
        put("id", id)
        put("type", "subscribe_trigger")
        // A state trigger with only entity ids fires on every change, attributes included.
        put("trigger", buildJsonObject {
            put("platform", "state")
            put("entity_id", buildJsonArray { entityIds.forEach { add(JsonPrimitive(it)) } })
        })
    }

    private fun unsubscribe(id: Int, subscription: Int) = buildJsonObject {
        put("id", id)
        put("type", "unsubscribe_events")
        put("subscription", subscription)
    }

    private fun lost(ev: WsEvent): SessionEnd = when (ev) {
        is WsEvent.Closed -> SessionEnd.Lost("closed ${ev.code}")
        is WsEvent.Failure -> SessionEnd.Lost(ev.error.message ?: ev.error.javaClass.simpleName)
        is WsEvent.Text -> SessionEnd.Lost(null)
    }

    /** Home Assistant may send several messages as one JSON array. */
    private fun parseMessages(text: String): List<JsonObject> = try {
        when (val el = json.parseToJsonElement(text)) {
            is JsonObject -> listOf(el)
            is JsonArray -> el.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.type(): String? = string("type")
}

/**
 * Estimates how far the local clock is ahead of Home Assistant's. Each change carries the
 * server time it happened; receive time minus that is skew plus network latency, so the
 * smallest recent sample is the best estimate. Without it, a TV whose clock is a few seconds
 * off would show a progress bar that is always a few seconds wrong.
 */
class ClockSkew(private val window: Int = 24) {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun addSample(localMinusServerMs: Long) {
        samples.addLast(localMinusServerMs)
        while (samples.size > window) samples.removeFirst()
    }

    /** Local minus server time in ms (0 until the first sample). */
    @get:Synchronized
    val offsetMs: Long get() = samples.minOrNull() ?: 0L
}
