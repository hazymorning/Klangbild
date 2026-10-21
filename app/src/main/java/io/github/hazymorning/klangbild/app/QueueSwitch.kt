package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.QueueDisplay

/**
 * The way the queue is to show that the screen does not show yet. A switch to or from Cover Flow
 * takes a few seconds, since the stage of the other way has to make its first scene before it
 * can come; until then the quick settings' and the menu's Queue value shimmer, so the choice is
 * seen to be on its way. It starts with the press (in the quick settings a choice is taken only
 * once the presses stop) and ends in the state that shows the new way. Only while a song is on
 * stage: over the clock, the setup page or a screen that says something is wrong nothing is made,
 * and a stage that comes later is no switch. Called under the controller's lock.
 */
internal class QueueSwitch {
    /** The setting chosen while another way showed, until the stage shows it. */
    private var coming: QueueDisplay? = null

    /** The setting changed to [queue] (the quick settings, the menu, a link or the launch's extras). */
    fun chosen(queue: QueueDisplay) {
        coming = queue
    }

    /**
     * What is on its way, with the state [ready] just worked out: a choice still [pending] in the
     * quick settings while it differs from the [setting], else the setting until [ready] shows
     * it. Nothing while no song is [onStage]; the switch is forgotten then.
     */
    fun onItsWay(ready: UiState, onStage: Boolean, setting: QueueDisplay, pending: QueueDisplay?): QueueDisplay? {
        if (!onStage) {
            coming = null
            return null
        }
        if (coming?.let { shows(ready, it) } == true) coming = null
        return pending?.takeIf { it != setting } ?: coming
    }

    companion object {
        /** Whether [ui] shows [queue]: Cover Flow once its strip is there, Off and the list once the single cover's stage is. */
        fun shows(ui: UiState, queue: QueueDisplay): Boolean = if (queue == QueueDisplay.FLOW) ui.flow != null else ui.stage != null
    }
}
