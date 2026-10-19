package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.domain.Screen

/** The stage the screen still holds from the song before, and when it has to go. */
internal object HeldStage {
    /**
     * Whether the stage held from before belongs to another song than [stage] and must not come
     * back: a song that comes after the clock, the setup page or the landscape waits for its own
     * scene, and the lights wait with it (until 2026.10 the song before came up first, and the
     * lights took its colors). While the stage shows, it stays until the new song's scene is
     * ready, and the change runs on the stage.
     */
    fun stale(before: UiState, stage: Screen.Stage?, landscape: LandscapeUi?): Boolean {
        if (stage == null || landscape != null) return false
        if (before.screen is Screen.Stage && before.landscape == null) return false
        val held = before.flow?.middle?.track ?: before.stage?.track ?: return false
        return held.key != stage.track.key
    }
}
