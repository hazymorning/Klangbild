package io.github.hazymorning.klangbild.domain

/**
 * Whether no one is in the room, by the presence sensors chosen on the setup page: once every one
 * of them has been off for [delayMs], and no key was pressed meanwhile. A sensor that can't tell
 * (unavailable, unknown, gone) counts as someone there, so the screen never goes dark for it.
 */
class Presence(private val delayMs: Long = DELAY_MS) {
    /** Since when every sensor has been off; null while one isn't. */
    private var offSince: Long? = null

    /** When a key was last pressed: someone is there. */
    private var keyAt: Long? = null

    /** Since when no one has been in the room, as [nobodyThere] decided it; null while someone is. */
    var nobodySince: Long? = null
        private set

    /** Decides again from the sensors' [states] at [t]; returns whether no one is in the room. */
    fun nobodyThere(states: List<String?>, t: Long): Boolean {
        if (states.isEmpty() || states.any { it != OFF }) {
            offSince = null
            nobodySince = null
            return false
        }
        val off = offSince ?: t.also { offSince = it }
        val from = maxOf(off, keyAt ?: off) + delayMs
        nobodySince = if (t >= from) nobodySince ?: from else null
        return nobodySince != null
    }

    /** A key was pressed at [t]: someone is in the room. */
    fun keyPressed(t: Long) {
        keyAt = t
        nobodySince = null
    }

    companion object {
        /** No one counts as there once the sensors have seen no one for this long. */
        const val DELAY_MS = 60_000L

        /** A sensor's state while it sees no one. */
        const val OFF = "off"
    }
}
