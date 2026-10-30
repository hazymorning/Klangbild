package io.github.hazymorning.klangbild.domain

/**
 * A notification from Home Assistant in the status line ("Laundry · Done"): the newest one, for
 * [showMs] from when it can first be seen. One that waits longer than [waitMs] to be seen (the
 * screen dark for no one in the room, a menu open) is left out. The commands that phones take as
 * messages are no words to show, and "clear_notification" takes the one showing away.
 */
class StatusMessage(private val showMs: Long = SHOW_MS, private val waitMs: Long = WAIT_MS) {
    private var line: String? = null
    private var arrivedAt = 0L
    private var seenSince: Long? = null

    /** A notification with [message] and [title] arrived at [t]. */
    fun arrive(title: String?, message: String, t: Long) {
        val words = message.trim()
        when {
            words == CLEAR -> line = null
            words in COMMANDS || words.startsWith(COMMAND_PREFIX) -> Unit
            else -> {
                line = listOfNotNull(title?.let(::oneLine)?.ifEmpty { null }, oneLine(words)).joinToString(TimeText.SEPARATOR)
                arrivedAt = t
                seenSince = null
            }
        }
    }

    /** The line to show at [t] while it can be [visible]; null while there is none. */
    fun line(visible: Boolean, t: Long): String? {
        val shown = line ?: return null
        val since = seenSince ?: if (visible) t.also { seenSince = it } else null
        val over = if (since == null) t - arrivedAt > waitMs else t - since >= showMs
        if (over) line = null
        return shown.takeIf { visible && !over }
    }

    /** The words on one line: line breaks and runs of spaces become one space. */
    private fun oneLine(s: String) = s.trim().replace(Regex("\\s+"), " ")

    companion object {
        /** How long a notification stays in the status line (ms). */
        const val SHOW_MS = 15_000L

        /** How long one may wait to be seen (ms). */
        const val WAIT_MS = 10 * 60_000L

        /** What the phone apps take as "take the notification away". */
        const val CLEAR = "clear_notification"

        /** Messages the phone apps take as commands, not words. */
        private val COMMANDS = setOf("request_location_update", "remove_channel", "clear_badge", "update_complications", "update_widgets")
        private const val COMMAND_PREFIX = "command_"
    }
}
