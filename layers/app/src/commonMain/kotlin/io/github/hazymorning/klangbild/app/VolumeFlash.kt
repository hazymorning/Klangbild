package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.domain.SourceState
import kotlin.concurrent.Volatile

/**
 * When the status line shows the volume for a moment: after a volume key, and after a volume
 * change reported from anywhere. Another speaker shown has a volume of its own, which is no
 * change. Called under the controller's lock, except [keyPressed].
 */
internal class VolumeFlash(private val now: () -> Long) {
    /** The volume and mute last reported for the speaker shown. */
    private var last: Pair<Float, Boolean>? = null

    @Volatile private var until = 0L

    /** A volume key was pressed: the volume shows at once, and Home Assistant reports the new one. */
    fun keyPressed() {
        until = now() + SHOWN_MS
    }

    /** [st] was reported at [t], while [shownSpeaker] was the speaker shown. */
    fun reported(st: SourceState, shownSpeaker: String?, t: Long) {
        if (st.speaker != shownSpeaker) last = null
        val p = st.player ?: return
        val volume = (p.volume ?: return) to p.muted
        if (last != null && volume != last) until = t + SHOWN_MS
        last = volume
    }

    /** A new connection: its first volume is no change. */
    fun reset() {
        last = null
    }

    fun shown(t: Long): Boolean = t < until

    companion object {
        /** How long a new volume stays in the status line. */
        const val SHOWN_MS = 3_500L
    }
}
