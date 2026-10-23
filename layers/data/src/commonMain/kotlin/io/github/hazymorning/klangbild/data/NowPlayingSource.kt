package io.github.hazymorning.klangbild.data

import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.domain.SourceState
import kotlinx.coroutines.flow.StateFlow

enum class RemoteCommand { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

interface NowPlayingSource {
    val state: StateFlow<SourceState>
    fun start()
    fun stop()
    suspend fun send(command: RemoteCommand)

    /** Plays on from [positionMs] in the item's own time. */
    suspend fun seek(positionMs: Long)

    /**
     * Adds what plays to Music Assistant's favorites (a station's song as the song); what went
     * wrong when it did not work.
     */
    suspend fun favorite(): Result<Unit> = Result.failure(UnsupportedOperationException("No favorites here"))

    /** Turns shuffle on or off; whether the call went out. */
    suspend fun shuffle(on: Boolean): Boolean = false

    /** Follows the entities of one's own (the buttons', their conditions', the values'), for [SourceState.entities]. */
    fun followEntities(ids: Set<String>) = Unit

    /** Does what [action] says in Home Assistant; whether Home Assistant took it. */
    suspend fun run(action: QuickAction): Boolean = false

    /** The link guests open to join the party ([SourceState.party]); null when there is none. */
    suspend fun partyUrl(): String? = null
}
