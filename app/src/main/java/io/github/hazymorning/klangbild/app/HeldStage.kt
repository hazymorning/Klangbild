package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.render.ArtworkScene

/**
 * The stage kept from before the clock, the setup page or the landscape, and what shows while
 * the song after them waits for its first scene. Called under the controller's lock.
 */
internal class HeldStage {
    /** A song waits for its first scene behind the quiet screen before it. */
    private var waiting = false

    /**
     * Whether the stage kept from [before] must not come back for [stage]: after the clock, the
     * setup page or the landscape, a song other than the one kept waits for its own scene, and so
     * does the same song when the wall has changed meanwhile (its scene no longer [fits]) or the
     * [view] has (only the other view's stage is kept). Until 2026.10 the song before came up
     * first and the lights took its colors, or the song came back on the old wall or in the
     * other view and crossed over. While the stage shows, it stays until the next song's scene
     * is ready, and the change runs on it.
     */
    fun stale(before: UiState, view: StageView, fits: (ArtworkScene) -> Boolean, stage: Screen.Stage?, landscape: Boolean): Boolean {
        if (stage == null || landscape) return false
        if (before.screen is Screen.Stage && before.landscape == null) return false
        val chosen = if (view == StageView.FLOW) before.flow?.stage else before.stage
        val other = if (view == StageView.FLOW) before.stage else before.flow?.stage
        if (chosen == null) return other != null
        return chosen.track.key != stage.track.key || !fits(chosen.scene)
    }

    /**
     * What shows: [now], or, while its song waits for its first scene after the clock or a
     * landscape ([stale] stage dropped), that screen as [before] showed it, so the screen never
     * waits in the dark and the keys, the quick settings and the lights act on what shows. A
     * screen that says something is wrong, or the setup page, goes through the dark instead:
     * what it said is no longer so.
     */
    fun shown(before: UiState, now: UiState, stale: Boolean): UiState {
        if (stale) waiting = true
        waiting = waiting && quiet(before) && now.screen is Screen.Stage && now.landscape == null && now.stage == null && now.flow == null
        return if (waiting) now.copy(screen = before.screen, landscape = before.landscape, downHint = false) else now
    }

    /** The clock (paused, or nothing plays) or a landscape. */
    private fun quiet(before: UiState): Boolean {
        if (before.landscape != null) return true
        val reason = (before.screen as? Screen.Rest)?.reason
        return reason == RestReason.PAUSED || reason == RestReason.IDLE
    }
}
