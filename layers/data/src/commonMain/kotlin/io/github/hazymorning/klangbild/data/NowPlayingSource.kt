package io.github.hazymorning.klangbild.data

import io.github.hazymorning.klangbild.data.ha.HaDevice
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.domain.SourceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class RemoteCommand { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

/** What arrives for this TV's notifications ([NowPlayingSource.notifyTo]). */
sealed interface Push {
    /** A notification sent to this TV: its title, if it has one, and its message. */
    data class Message(val title: String?, val message: String) : Push

    /** Home Assistant refused the notifications: the registration is gone, or belongs to another user. */
    data object Refused : Push
}

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

    /** Follows the speakers of other rooms, whose music shows while nothing plays here ([SourceState.elsewhere]). */
    fun followOthers(ids: List<String>) = Unit

    /** Does what [action] says in Home Assistant; whether Home Assistant took it. */
    suspend fun run(action: QuickAction): Boolean = false

    /** The link guests open to join the party ([SourceState.party]); null when there is none. */
    suspend fun partyUrl(): String? = null

    /**
     * Registers this TV as [device] (known by [deviceId] across registrations) to be sent
     * notifications, and returns the registration's webhook id: a secret.
     */
    suspend fun register(device: HaDevice, deviceId: String): String = throw UnsupportedOperationException("No notifications here")

    /** Receives the notifications of the registration [webhookId] ([notifications]); null receives none. */
    fun notifyTo(webhookId: String?) = Unit

    /** The notifications sent to this TV while [notifyTo] receives them. */
    val notifications: Flow<Push> get() = emptyFlow()
}
