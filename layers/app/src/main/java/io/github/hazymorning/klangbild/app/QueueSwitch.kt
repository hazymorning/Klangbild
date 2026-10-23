package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.QueueDisplay

/**
 * The queue view that was chosen but isn't on screen yet. A switch to or from Cover Flow takes a
 * few seconds, since the other view has to make its first scene before it can come. Until then
 * the Queue value in the quick settings and the menu shimmers, so it's clear the choice is on its
 * way. It starts with the press (the quick settings apply a choice only once the presses stop)
 * and ends in the state that shows the new view. It only exists while a song is on stage: over
 * the clock, the setup page or a screen that says something is wrong nothing is made, and a stage
 * that comes later doesn't count as a switch. Called under the controller's lock.
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
