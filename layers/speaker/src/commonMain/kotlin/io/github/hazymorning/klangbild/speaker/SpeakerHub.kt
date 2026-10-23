package io.github.hazymorning.klangbild.speaker

import io.github.hazymorning.klangbild.domain.SpeakerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/** This TV's speaker while it runs: its state for the rest of the app, and the speaker itself. */
class SpeakerHub {
    private val _status = MutableStateFlow(SpeakerStatus())
    val status: StateFlow<SpeakerStatus> = _status.asStateFlow()

    @Volatile var speaker: Speaker? = null

    fun publish(status: SpeakerStatus) {
        _status.value = status
    }
}
