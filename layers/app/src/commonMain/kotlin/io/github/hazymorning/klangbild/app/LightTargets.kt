package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.domain.FieldClock
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.SunClock
import io.github.hazymorning.klangbild.lights.CoverLightColors
import io.github.hazymorning.klangbild.lights.LightSync
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.FieldScene

/**
 * What the lights are to show: the colors of the scene on stage, one color for each light,
 * worked out once per scene.
 */
internal class LightTargets(private val fieldClock: FieldClock, private val now: () -> Long) {
    /** The colors for a cover's field, made once per field (a new object fades the lights to it). */
    private var coverColors: Pair<Any, CoverLightColors>? = null

    /** The target for the lights now, or null when they have nothing to show. */
    fun target(s: Settings, ui: UiState, stage: Screen.Stage?, playing: Boolean): LightSync.Target? {
        if (!s.roomLights.isSet || stage == null) return null
        // By the sun: full brightness by day, dimmer from dusk, when the room around the TV is dark.
        val night = night(ui.source)
        // While a switch to or from Cover Flow waits for its first scene, what showed before still shows.
        val shown = if (s.queue == QueueDisplay.FLOW) ui.flow?.middle?.stage ?: ui.stage else ui.stage ?: ui.flow?.middle?.stage
        val field = shown?.scene?.field ?: return null
        return LightSync.Target(
            lights = s.roomLights,
            level = s.roomLightBrightness,
            colors = coverColors(field, s.motion.speed > 0f),
            song = shown.track.key,
            playing = playing,
            night = night,
            motion = s.motion.speed,
            fieldTime = fieldClock::now,
            onlyOn = s.lightsOnlyOn,
        )
    }

    private fun coverColors(field: FieldScene, moving: Boolean): CoverLightColors {
        val key = field to moving
        coverColors?.takeIf { it.first == key }?.let { return it.second }
        return CoverLightColors(field, moving).also { coverColors = key to it }
    }

    /** 0 by day to 1 at night, by the sun (Home Assistant's elevation, else the local clock): how far the lights dim. */
    private fun night(src: SourceState): Float {
        val elevation = src.sunElevation ?: SunClock.elevationAt(now())
        return smoothstep(DAY_ELEVATION, NIGHT_ELEVATION, elevation.toFloat())
    }

    companion object {
        /** The lights are at full brightness with the sun this high (degrees), at their night level from this low. */
        const val DAY_ELEVATION = 6f
        const val NIGHT_ELEVATION = -8f
    }
}
