package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.data.WebSocketLink
import io.github.hazymorning.klangbild.data.orNull
import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.json.string
import io.ktor.client.HttpClient
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
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
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Where and how to reach Home Assistant. */
data class HaConfig(val auth: HaAuth) {
    val baseUrl: String get() = auth.baseUrl
}

/** Connection state as the rest of the app sees it, never raw exceptions. */
sealed interface HaStatus {
    data object Stopped : HaStatus
    data object Connecting : HaStatus
    data object Connected : HaStatus

    /**
     * Home Assistant refused the credentials. The connection stays down until it is started again,
     * since Home Assistant counts every attempt as a failed login.
     */
    data object AuthFailed : HaStatus

    /** Lost or failed: waiting before the next attempt. */
    data object Waiting : HaStatus
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
    private val http: HttpClient,
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
     * Also follows [ids] (a group's other speakers, the lights, the quick settings' entities, this
     * TV's speaker). Their states appear in [entities] and are followed again after a reconnect.
     */
    fun followAlso(ids: Set<String>) {
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

    /**
     * The unique ids the entity registry has for [ids] (`config/entity_registry/get_entries`,
     * open to every user): for a Music Assistant speaker, its player's id in Music Assistant.
     * Throws when Home Assistant cannot say.
     */
    suspend fun uniqueIds(ids: List<String>): Map<String, String> {
        val s = session ?: error("not connected")
        val answer = s.command(handshakeTimeoutMs) { id ->
            buildJsonObject {
                put("id", id)
                put("type", "config/entity_registry/get_entries")
                put("entity_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            }
        } as? JsonObject ?: throw IOException("Unexpected answer to get_entries")
        return answer.mapNotNull { (entity, entry) -> ((entry as? JsonObject)?.string("unique_id"))?.let { entity to it } }.toMap()
    }

    /** Reads the entity registry as listed for display again ([registry]): an entity Home Assistant has added since. */
    suspend fun refreshRegistry() {
        val s = session ?: return
        registryOf(optional(s) { id -> buildJsonObject { put("id", id); put("type", "config/entity_registry/list_for_display") } })?.let { _registry.value = it }
    }

    /**
     * Skips the rest of the wait before the next attempt (the network just came back, say). Only
     * while waiting: a wake-up kept from before would skip the next wait for nothing.
     */
    fun retryNow() {
        if (_status.value is HaStatus.Waiting) wakeUp.trySend(Unit)
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
        while (true) {
            _status.value = HaStatus.Connecting
            val end = try {
                runSession(config, entityIds) {
                    backoff.reset()
                    freshTokenTried = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: HaAuthRejectedException) {
                SessionEnd.Refused()
            } catch (_: Exception) {
                SessionEnd.Lost
            } finally {
                session = null
            }
            when (end) {
                is SessionEnd.Refused -> {
                    if (end.token != null && !freshTokenTried && config.auth.refreshable) {
                        freshTokenTried = true
                        config.auth.invalidate(end.token)
                        continue
                    }
                    _status.value = HaStatus.AuthFailed
                    _entities.value = emptyMap()
                    return
                }
                SessionEnd.Lost -> {
                    val delayMs = backoff.nextDelayMs()
                    _status.value = HaStatus.Waiting
                    select<Unit> {
                        wakeUp.onReceive { }
                        onTimeout(delayMs) { }
                    }
                }
            }
        }
    }

    private sealed interface SessionEnd {
        /** Home Assistant refused the credentials; [token] is the access token its WebSocket refused, if that was the cause. */
        data class Refused(val token: String? = null) : SessionEnd
        data object Lost : SessionEnd
    }

    private sealed interface WsEvent {
        data class Text(val text: String) : WsEvent

        /** The connection was closed or failed. */
        data object Ended : WsEvent
    }

    /** States the REST API returned ([found]) and the IDs it says don't exist ([missing]). */
    private class RestStates(val found: List<EntityState>, val missing: Set<String>)

    /** A command's result, which can be null, wrapped so that a timeout (null from `withTimeoutOrNull`) can be told from it. */
    private class Answer(val value: JsonElement?)

    private inner class Session(val socket: WebSocketLink) {
        private val nextId = atomic(1)
        private val lock = SynchronizedObject()
        private val pending = HashMap<Int, CompletableDeferred<JsonElement?>>()
        @Volatile var lastPong = 0L

        fun send(obj: JsonObject): Boolean = socket.send(obj.toString())

        /** The call waiting for the answer [id], if any. */
        fun waiter(id: Int): CompletableDeferred<JsonElement?>? = synchronized(lock) { pending[id] }

        /** Every call still waiting gives up (the connection ended). */
        fun cancelAll() = synchronized(lock) { pending.values.toList() }.forEach { it.cancel() }

        /**
         * Sends a command and waits for its answer. No answer in time, or a connection that
         * ends under the call, is the call's failure ([IOException]), never a cancellation that
         * would silently end the caller's coroutine; only the caller's own cancellation passes.
         */
        suspend fun command(timeoutMs: Long, build: (Int) -> JsonObject): JsonElement? {
            val id = nextId()
            val deferred = CompletableDeferred<JsonElement?>()
            synchronized(lock) { pending[id] = deferred }
            try {
                if (!send(build(id))) throw IOException("socket closed")
                val answer = withTimeoutOrNull(timeoutMs) { Answer(deferred.await()) }
                    ?: throw HaNoAnswerException(timeoutMs)
                return answer.value
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("connection lost", e)
            } finally {
                synchronized(lock) { pending.remove(id) }
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
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val socket = WebSocketLink(this, http, HaUrls.webSocketUrl(config.baseUrl), object : WebSocketLink.Listener {
            override fun onText(text: String) {
                events.trySend(WsEvent.Text(text))
            }

            override fun onClosed(code: Int, reason: String) {
                events.trySend(WsEvent.Ended)
            }

            override fun onFailure(error: Throwable) {
                events.trySend(WsEvent.Ended)
            }
        })
        try {
            signIn(events, socket, token) ?: Subscriptions(this, Session(socket), HaRest(http, config.auth), entityIds, onSubscribed).run(events)
        } finally {
            socket.cancel()
        }
    }

    /** Signs in on [socket] with [token]: null once Home Assistant takes it, else how the session ends. */
    private suspend fun signIn(events: ReceiveChannel<WsEvent>, socket: WebSocketLink, token: String): SessionEnd? {
        val greeting = withTimeoutOrNull(handshakeTimeoutMs) { events.receive() } as? WsEvent.Text ?: return SessionEnd.Lost
        if (parseMessages(greeting.text).firstOrNull()?.type() != "auth_required") return SessionEnd.Lost
        socket.send(
            buildJsonObject {
                put("type", "auth")
                put("access_token", token)
            }.toString(),
        )
        val answer = withTimeoutOrNull(handshakeTimeoutMs) { events.receive() } as? WsEvent.Text ?: return SessionEnd.Lost
        return when (parseMessages(answer.text).firstOrNull()?.type()) {
            "auth_ok" -> null
            "auth_invalid" -> SessionEnd.Refused(token)
            else -> SessionEnd.Lost
        }
    }

    /**
     * What a signed-in session follows: the main entities and the extra ones ([followAlso]), by a
     * state trigger or the events feed ([Feed]), and their states over REST, until the connection ends.
     */
    private inner class Subscriptions(
        private val sessionScope: CoroutineScope,
        private val s: Session,
        private val rest: HaRest,
        entityIds: List<String>,
        private val onSubscribed: () -> Unit,
    ) {
        private val main = entityIds.toSet()
        private val store = EntityStore()
        private var feed = Feed.TRIGGER
        private var mainSub = s.nextId()
        private var subscribed = false
        private var extraSub: Int? = null
        private var extras: Set<String> = emptySet()

        /** The main entities and the extra ones. */
        private var followed: Set<String> = main
        private val restResults = Channel<RestStates>(Channel.UNLIMITED)
        private val extraChanges = Channel<Set<String>>(Channel.CONFLATED)

        /** Subscribes, then handles what arrives until the connection ends; how it ended. */
        suspend fun run(events: ReceiveChannel<WsEvent>): SessionEnd {
            session = s
            s.lastPong = now()
            s.send(subscribeTrigger(mainSub, main))
            // Asked once per session without holding up the subscription: Home Assistant's
            // configuration (for its temperature unit) and the entity registry's display list.
            sessionScope.launch {
                (optional(s) { id -> buildJsonObject { put("id", id); put("type", "get_config") } } as? JsonObject)?.let { _config.value = it }
            }
            sessionScope.launch {
                val list = optional(s) { id -> buildJsonObject { put("id", id); put("type", "config/entity_registry/list_for_display") } }
                registryOf(list)?.let { _registry.value = it }
            }
            val pinger = sessionScope.launch { ping() }
            val extraWatcher = sessionScope.launch { extraIds.collect { extraChanges.send(it) } }
            try {
                while (true) {
                    val end = select<SessionEnd?> {
                        events.onReceive { event -> if (event is WsEvent.Text) onText(event.text) else SessionEnd.Lost }
                        restResults.onReceive { states ->
                            onRest(states)
                            null
                        }
                        if (subscribed) {
                            extraChanges.onReceive { ids ->
                                if (ids != extras) followExtras(ids)
                                null
                            }
                        }
                    }
                    if (end != null) return end
                }
            } finally {
                pinger.cancel()
                extraWatcher.cancel()
                s.cancelAll()
            }
        }

        /** Pings every [pingIntervalMs]; without a pong within [pongTimeoutMs] the connection is dead, and ends. */
        private suspend fun ping() {
            while (true) {
                delay(pingIntervalMs)
                val sentAt = now()
                s.send(
                    buildJsonObject {
                        put("id", s.nextId())
                        put("type", "ping")
                    },
                )
                delay(pongTimeoutMs)
                if (s.lastPong < sentAt) {
                    s.socket.cancel()
                    return
                }
            }
        }

        /** A message from Home Assistant; how the session ends, if it does. */
        private fun onText(text: String): SessionEnd? {
            // The events feed carries every state change in the house. Skip messages that name no
            // followed entity, unless they answer a command or a ping.
            if (feed == Feed.EVENTS && subscribed && followed.none { text.contains("\"$it\"") } && !ANSWER.containsMatchIn(text)) return null
            for (msg in parseMessages(text)) {
                val id = (msg["id"] as? JsonPrimitive)?.intOrNull
                when (msg.type()) {
                    "event" -> if (id != null && (id == mainSub || id == extraSub)) onEvent(msg)
                    "result" -> onResult(id, msg)?.let { return it }
                    "pong" -> s.lastPong = now()
                }
            }
            return null
        }

        /** A followed entity's new state, or its removal. */
        private fun onEvent(msg: JsonObject) {
            val event = msg["event"] as? JsonObject ?: return
            val (entity, state) = changeOf(event, feed) ?: return
            if (entity !in followed) return
            val changed = if (state == null) store.remove(entity) else store.put(state)
            if (state != null) {
                clockSkew.addSample(now() - (state.lastUpdated * 1000).toLong())
                if (entity in _missing.value) _missing.value = _missing.value - entity
            }
            if (changed) publish()
        }

        /** A command's result, for a call that waits for it or for a subscription; ends the session when the main subscription failed. */
        private fun onResult(id: Int?, msg: JsonObject): SessionEnd? {
            val ok = (msg["success"] as? JsonPrimitive)?.booleanOrNull == true
            val error = msg["error"] as? JsonObject
            val waiter = id?.let { s.waiter(it) }
            when {
                waiter != null -> if (ok) {
                    waiter.complete(msg["result"])
                } else {
                    waiter.completeExceptionally(HaCommandException(error?.string("code") ?: "unknown", error?.string("message") ?: "error"))
                }
                id == mainSub && ok -> {
                    subscribed = true
                    _status.value = HaStatus.Connected
                    onSubscribed()
                    readStates(main)
                }
                // Not an administrator: every state change, the followed ones kept.
                id == mainSub && feed == Feed.TRIGGER && error?.string("code") == "unauthorized" -> {
                    feed = Feed.EVENTS
                    mainSub = s.nextId()
                    s.send(
                        buildJsonObject {
                            put("id", mainSub)
                            put("type", "subscribe_events")
                            put("event_type", "state_changed")
                        },
                    )
                }
                id == mainSub -> return SessionEnd.Lost
                id != null && id == extraSub && !ok -> extraSub = null
            }
            return null
        }

        /** States read over REST. An event may have overtaken them; an entity Home Assistant doesn't have is missing. */
        private fun onRest(r: RestStates) {
            var changed = false
            for (st in r.found) if (st.entityId in followed) changed = store.put(st) or changed
            val missing = r.missing.intersect(followed)
            if (missing.isNotEmpty()) {
                for (id in missing) changed = store.remove(id) or changed
                _missing.value = _missing.value + missing
            }
            val known = r.found.map { it.entityId }.toSet()
            if (known.any { it in _missing.value }) _missing.value = _missing.value - known
            if (changed || missing.isNotEmpty()) publish()
        }

        /** Follows [ids] besides the main entities, with a trigger of their own. */
        private fun followExtras(ids: Set<String>) {
            val gone = extras - ids - main
            val added = ids - extras - main
            extras = ids
            followed = main + ids
            if (feed == Feed.TRIGGER) {
                extraSub?.let { old -> s.send(unsubscribe(s.nextId(), old)) }
                extraSub = null
                val own = ids - main
                if (own.isNotEmpty()) {
                    val id = s.nextId()
                    s.send(subscribeTrigger(id, own))
                    extraSub = id
                }
            }
            if (gone.isNotEmpty()) {
                store.retain(followed)
                _missing.value = _missing.value - gone
                publish()
            }
            readStates(added)
        }

        /** Reads the states of [ids] over REST; what it can't read is read again a few times. */
        private fun readStates(ids: Set<String>) {
            if (ids.isEmpty()) return
            sessionScope.launch {
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

        private fun publish() {
            _entities.value = store.snapshot()
        }
    }

    /** A command whose answer is welcome but not needed: null when it fails. */
    private suspend fun optional(s: Session, build: (Int) -> JsonObject): JsonElement? = orNull { s.command(handshakeTimeoutMs, build) }

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

    private fun subscribeTrigger(id: Int, entityIds: Iterable<String>) = buildJsonObject {
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

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        /** A message that answers a command or a ping, however Home Assistant spaces its JSON. */
        val ANSWER = Regex("\"type\"\\s*:\\s*\"(result|pong)\"")
    }
}

/**
 * Estimates how far the local clock is ahead of Home Assistant's. Each change carries the
 * server time it happened; receive time minus that is skew plus network latency, so the
 * smallest recent sample is the best estimate. Without it, a TV whose clock is a few seconds
 * off would show a progress bar that is always a few seconds wrong.
 */
class ClockSkew(private val window: Int = 24) {
    private val lock = SynchronizedObject()
    private val samples = ArrayDeque<Long>()

    fun addSample(localMinusServerMs: Long): Unit = synchronized(lock) {
        samples.addLast(localMinusServerMs)
        while (samples.size > window) samples.removeFirst()
    }

    /** Local minus server time in ms (0 until the first sample). */
    val offsetMs: Long get() = synchronized(lock) { samples.minOrNull() ?: 0L }
}
