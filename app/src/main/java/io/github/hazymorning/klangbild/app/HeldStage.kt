package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.render.ArtworkScene

/**
 * The stage kept from before the clock, the setup page or the landscape, and what shows while a
 * song waits for its first scene.
 */
internal object HeldStage {
    /**
     * Whether the stage kept from [before] must not come back for [stage]: after the clock, the
     * setup page or the landscape, a song other than the one kept waits for its own scene, and so
     * does the same song when the wall has changed meanwhile (its scene no longer [fits]) or the
     * queue has gone to or from Cover Flow ([flow]: only the stage of the other is kept). Until
     * 2026.10 the song before came up first and the lights took its colors, or the song came back
     * on the old wall or in the other composition and crossed over. While the stage shows, it
     * stays until the next song's scene is ready, and the change runs on it.
     */
    fun stale(before: UiState, flow: Boolean, fits: (ArtworkScene) -> Boolean, stage: Screen.Stage?, landscape: Boolean): Boolean {
        if (stage == null || landscape) return false
        if (before.screen is Screen.Stage && before.landscape == null) return false
        val chosen = if (flow) before.flow?.stage else before.stage
        val other = if (flow) before.stage else before.flow?.stage
        if (chosen == null) return other != null
        return chosen.track.key != stage.track.key || !fits(chosen.scene)
    }

    /**
     * What shows: [now], or, while its song has nothing to show yet (its first scene is being
     * made) after the clock or a landscape, that screen as [before] showed it. So the screen
     * never waits in the dark, and the keys, the quick settings and the lights act on what
     * shows; over a landscape the song keys act on what plays, as always, so only the landscape
     * is kept. A screen that says something is wrong, or the setup page, goes through the dark
     * instead: what it said is no longer so.
     */
    fun shown(before: UiState, now: UiState): UiState {
        if (!quiet(before) || now.screen !is Screen.Stage || now.landscape != null || now.stage != null || now.flow != null) return now
        if (before.landscape != null) return now.copy(landscape = before.landscape, downHint = false)
        return now.copy(screen = before.screen, downHint = false)
    }

    /** The clock (paused, or nothing plays) or a landscape. */
    private fun quiet(before: UiState): Boolean {
        if (before.landscape != null) return true
        val reason = (before.screen as? Screen.Rest)?.reason
        return reason == RestReason.PAUSED || reason == RestReason.IDLE
    }
}
