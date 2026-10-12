package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.domain.FieldClock
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.domain.SunClock
import io.github.hazymorning.klangbild.lights.CoverLightColors
import io.github.hazymorning.klangbild.lights.LightSync
import io.github.hazymorning.klangbild.lights.VistaLightColors
import io.github.hazymorning.klangbild.render.FieldScene
import io.github.hazymorning.klangbild.vista.PaperLight
import io.github.hazymorning.klangbild.vista.Place
import io.github.hazymorning.klangbild.vista.VistaClock
import io.github.hazymorning.klangbild.vista.Weather
import io.github.hazymorning.klangbild.vista.WeatherMood
import io.github.hazymorning.klangbild.vista.World
import java.time.ZoneId
import java.util.Calendar

/**
 * What the lights are to show: the window view's light while it shows, else the colors of the
 * scene on stage, one color for each light. The colors are worked out once per scene, and for
 * the window view once per step of its sky's time.
 */
internal class LightTargets(
    private val fieldClock: FieldClock,
    private val vistaClock: VistaClock,
    private val now: () -> Long,
) {
    /** The colors for a cover's field, made once per field (a new object fades the lights to it). */
    private var coverColors: Pair<Any, CoverLightColors>? = null

    /** The colors for the window view as it looked when they were worked out. */
    private var vistaColors: Pair<List<Any?>, VistaLightColors>? = null

    /** The target for the lights now, or null when they have nothing to show. */
    fun target(s: Settings, ui: UiState, stage: Screen.Stage?, playing: Boolean, vista: VistaUi?): LightSync.Target? {
        if (!s.roomLights.isSet) return null
        // By the sun: full brightness by day, dimmer from dusk, when the room around the TV is dark.
        val night = night(ui.source)
        if (vista != null) {
            return LightSync.Target(
                lights = s.roomLights,
                level = s.roomLightLevel,
                colors = vistaColors(ui.source, vista.miniPlayer),
                song = VISTA_SONG,
                playing = true,
                night = night,
                warmth = night,
                motion = 0f,
                fieldTime = fieldClock::now,
                vista = true,
                onlyOn = s.lightsOnlyOn,
            )
        }
        if (stage == null) return null
        val shown = if (s.view == StageView.FLOW) ui.flow?.middle?.stage else ui.stage
        val field = shown?.scene?.field ?: return null
        return LightSync.Target(
            lights = s.roomLights,
            level = s.roomLightLevel,
            colors = coverColors(field, s.motion.speed > 0f),
            song = shown.track.key,
            playing = playing,
            night = night,
            warmth = night,
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

    /**
     * The colors for the window view as it looks now (its sky's moment, the place, the
     * weather), worked out again only when the sky has moved on a little: the lights follow in
     * long fades anyway.
     */
    private fun vistaColors(src: SourceState, mini: MiniPlayer): VistaLightColors {
        val zone = ZoneId.systemDefault()
        val place = src.place?.let { (lat, lon) -> Place(lat, lon) } ?: World.guessPlace(zone)
        val sky = vistaClock.sky()
        val weather = WeatherMood.of(src.weather, src.cloudCoverage, src.windKmh) ?: Weather.FAIR
        // The fire burns on the side of the clearing away from the label (without one, right of the middle).
        val fireOnRight = mini != MiniPlayer.RIGHT
        val key = listOf(sky / VISTA_STEP_MS, weather.copy(wind = 0f), place, fireOnRight)
        vistaColors?.takeIf { it.first == key }?.let { return it.second }
        val look = PaperLight.look(World.sky(sky, place, zone, weather).first)
        return VistaLightColors(look, fireOnRight).also { vistaColors = key to it }
    }

    /** 0 by day to 1 at night, by the sun (Home Assistant's elevation, else the local clock): how far the lights dim. */
    private fun night(src: SourceState): Float {
        val elevation = src.sunElevation ?: Calendar.getInstance().apply { timeInMillis = now() }
            .let { SunClock.elevation(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
        val t = ((DAY_ELEVATION - elevation.toFloat()) / (DAY_ELEVATION - NIGHT_ELEVATION)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        /** The lights' "song" while the window view shows. */
        const val VISTA_SONG = "\u0000vista"

        /** The lights are at full brightness with the sun this high (degrees), at their night level from this low. */
        const val DAY_ELEVATION = 6f
        const val NIGHT_ELEVATION = -8f

        /** The window view's light for the lights is worked out again after this much of its sky's time. */
        const val VISTA_STEP_MS = 30_000L
    }
}
