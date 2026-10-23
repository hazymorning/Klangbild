package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * This TV's own speaker as Home Assistant has it ([entity]), found from the id Music Assistant
 * knows it by ([SpeakerStatus.clientId]): Music Assistant names the player around that id
 * (`players/all`, from a few seconds after the speaker first connects), and Home Assistant's
 * entity registry knows that player's entity by its unique id
 * (`config/entity_registry/get_entries`, open to every user). Until both have it, they are asked
 * again, less and less often (at most a couple of minutes apart). A speaker switched off has none.
 */
internal class OwnSpeaker(
    private val scope: CoroutineScope,
    private val connection: HaConnection,
    private val ma: MaClient,
    private val speaker: StateFlow<SpeakerStatus>,
    private val backoff: () -> Backoff = { Backoff(FIRST_RETRY_MS, LAST_RETRY_MS) },
    /** Called when [entity] changes. */
    private val changed: () -> Unit,
) {
    @Volatile var entity: String? = null
        private set

    private var job: Job? = null

    fun start() {
        job = scope.launch {
            // Looked for again from the start once Home Assistant's registry is there; until then
            // each try reads the registry itself, so a first read that failed is made good.
            combine(speaker.map { it.clientId }, connection.registry.map { it != null }) { id, ready -> id to ready }
                .distinctUntilChanged()
                .collectLatest { (id, _) ->
                    if (id == null) set(null) else find(id)
                }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun set(id: String?) {
        if (id == entity) return
        entity = id
        changed()
    }

    private suspend fun find(clientId: String) {
        val wait = backoff()
        while (true) {
            val found = attempt { ma.playerOf(clientId)?.let { entityOf(it) } }
            if (found != null) {
                set(found)
                return
            }
            delay(wait.nextDelayMs())
        }
    }

    /** The Music Assistant entity whose unique id is [player]; the registry is read anew for one Home Assistant has added since. */
    private suspend fun entityOf(player: String): String? {
        matching(player)?.let { return it }
        connection.refreshRegistry()
        return matching(player)
    }

    private suspend fun matching(player: String): String? {
        val candidates = connection.registry.value
            ?.filter { it.platform == HaSource.MA_PLATFORM && it.entityId.startsWith("media_player.") }
            ?.map { it.entityId }
        if (candidates.isNullOrEmpty()) return null
        return connection.uniqueIds(candidates).entries.firstOrNull { it.value == player }?.key
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Music Assistant names a new speaker's player about 15 seconds after it first connects. */
        const val FIRST_RETRY_MS = 15_000L
        const val LAST_RETRY_MS = 2 * 60_000L
    }
}
