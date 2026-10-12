package io.github.hazymorning.klangbild.config

import androidx.annotation.StringRes
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.ma.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Texts

/** Sections of the settings menu, in order. */
enum class MenuSection(@StringRes val title: Int) {
    PICTURE(R.string.section_picture),
    INFO(R.string.section_info),
    EFFECTS(R.string.section_effects),
    VISTA(R.string.section_vista),
    REMOTE(R.string.section_remote),
    TIMER(R.string.section_timer),
    CONNECTION(R.string.section_connection),
    SYSTEM(R.string.section_system),
}

/** One line of the settings menu, in the menu's order; [label] is 0 for a line named after what it connects to. */
enum class MenuEntry(val section: MenuSection, @StringRes val label: Int) {
    VIEW(MenuSection.PICTURE, R.string.entry_view),
    APPEARANCE(MenuSection.PICTURE, R.string.entry_appearance),
    CLOCK(MenuSection.INFO, R.string.entry_clock),
    TIME_LINE(MenuSection.INFO, R.string.entry_progress),
    NEXT(MenuSection.INFO, R.string.entry_next),
    ALBUM(MenuSection.INFO, R.string.entry_album),
    GROUP(MenuSection.INFO, R.string.entry_group),
    VOLUME(MenuSection.INFO, R.string.entry_volume),
    VALUES(MenuSection.INFO, R.string.entry_values),
    BACKGROUND(MenuSection.EFFECTS, R.string.entry_background),
    LIGHT(MenuSection.EFFECTS, R.string.entry_intensity),
    MOTION(MenuSection.EFFECTS, R.string.entry_motion),
    LIGHTS(MenuSection.EFFECTS, R.string.entry_lights),
    LIGHTS_ONLY_ON(MenuSection.EFFECTS, R.string.entry_off_lights),
    VISTA_PAUSE(MenuSection.VISTA, R.string.entry_vista_pause),
    VISTA_PLAYING(MenuSection.VISTA, R.string.entry_vista_playing),
    MINI_PLAYER(MenuSection.VISTA, R.string.entry_mini_player),
    REMOTE(MenuSection.REMOTE, R.string.entry_remote),
    UP_DOWN(MenuSection.REMOTE, R.string.entry_up_down),
    SPOKEN(MenuSection.REMOTE, R.string.entry_spoken),
    SLEEP_TIMER(MenuSection.TIMER, R.string.entry_sleep_timer),
    SLEEP_AFTER(MenuSection.TIMER, R.string.entry_sleep_after),
    SLEEP_ACTION(MenuSection.TIMER, R.string.entry_sleep_action),
    CONNECTION(MenuSection.CONNECTION, 0),
    MUSIC_ASSISTANT(MenuSection.CONNECTION, 0),
    LANGUAGE(MenuSection.SYSTEM, R.string.entry_language),
}

/**
 * What the menu needs to know besides the settings: the connections and the wall as they are
 * now, the sleep timer ([sleep], pausing at [sleepAt]), what kind of thing plays, and the
 * language chosen.
 */
data class MenuContext(
    val link: LinkState? = null,
    val appearance: Appearance = Appearance.DARK,
    val ma: MaLink? = null,
    val sleep: SleepChoice = SleepChoice.OFF,
    val sleepAt: Long? = null,
    val kind: MediaKind? = null,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

/**
 * A line as the menu shows it: its label, the value in words and what the option does; [level]
 * (0 to 1) for a value shown on a slider.
 */
data class MenuRow(val entry: MenuEntry, val label: String, val value: String, val description: String, val level: Float? = null)

/**
 * The settings menu, operated with the remote: up and down choose a line, left and right (or
 * OK) change its value at once; there is nothing to confirm. Every text comes from the
 * resources, in the language shown.
 */
object SettingsMenu {
    val entries: List<MenuEntry> = MenuEntry.entries

    /** Minutes offered for the sleep timeout. */
    val sleepChoices = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

    fun rows(s: Settings, ctx: MenuContext, t: Texts): List<MenuRow> =
        entries.map { MenuRow(it, label(it, t), value(it, s, ctx, t), description(it, s, ctx, t), level(it, s)) }

    /** A line's label; the two connection lines are named after what they connect to. */
    fun label(e: MenuEntry, t: Texts): String = when (e) {
        MenuEntry.CONNECTION -> HOME_ASSISTANT
        MenuEntry.MUSIC_ASSISTANT -> MUSIC_ASSISTANT
        else -> t[e.label]
    }

    /** Where a slider stands: the lights' brightness. */
    fun level(e: MenuEntry, s: Settings): Float? = when (e) {
        MenuEntry.LIGHTS -> s.roomLightLevel.brightness.takeIf { s.roomLights.isSet }
        else -> null
    }

    fun value(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts): String = when (e) {
        MenuEntry.VIEW -> t[if (s.view == StageView.FLOW) R.string.view_flow else R.string.view_single]
        MenuEntry.CLOCK -> onOff(s.showClock, t)
        MenuEntry.TIME_LINE -> onOff(s.showTimeLine, t)
        MenuEntry.NEXT -> onOff(s.showNext, t)
        MenuEntry.ALBUM -> onOff(s.showAlbum, t)
        MenuEntry.GROUP -> onOff(s.showGroup, t)
        MenuEntry.VOLUME -> t[
            when (s.volumeDisplay) {
                VolumeDisplay.ON_CHANGE -> R.string.volume_on_change
                VolumeDisplay.ALWAYS -> R.string.volume_always
                VolumeDisplay.OFF -> R.string.off
            },
        ]
        MenuEntry.VALUES -> if (s.ownValues.isEmpty()) t[R.string.set_up] else onOff(s.showValues, t)
        MenuEntry.BACKGROUND -> t[if (s.background == BackgroundMode.COVER) R.string.background_cover else R.string.background_plain]
        MenuEntry.MOTION -> t[
            when (s.motion) {
                MotionLevel.OFF -> R.string.off
                MotionLevel.GENTLE -> R.string.motion_slow
                MotionLevel.MEDIUM -> R.string.motion_medium
                MotionLevel.LIVELY -> R.string.motion_fast
            },
        ]
        MenuEntry.LIGHTS -> when {
            !s.roomLights.isSet -> t[R.string.set_up]
            s.roomLightLevel == RoomLightLevel.OFF -> t[R.string.off]
            else -> t.percent(s.roomLightLevel.percent)
        }
        MenuEntry.LIGHTS_ONLY_ON -> t[
            when {
                !s.roomLights.isSet -> R.string.set_up
                s.lightsOnlyOn -> R.string.off_lights_leave
                else -> R.string.off_lights_turn_on
            },
        ]
        MenuEntry.APPEARANCE -> t[
            when (s.appearance) {
                AppearanceMode.AUTO -> R.string.appearance_auto
                AppearanceMode.LIGHT -> R.string.appearance_light
                AppearanceMode.DARK -> R.string.appearance_dark
            },
        ]
        MenuEntry.LIGHT -> t[
            when (s.light) {
                LightLevel.STRONG -> R.string.intensity_strong
                LightLevel.NORMAL -> R.string.intensity_normal
                LightLevel.SUBTLE -> R.string.intensity_low
                LightLevel.OFF -> R.string.off
            },
        ]
        MenuEntry.VISTA_PAUSE -> delay(s.vistaPauseSeconds, t)
        MenuEntry.VISTA_PLAYING -> delay(s.vistaPlayingSeconds, t)
        MenuEntry.MINI_PLAYER -> t[
            when (s.miniPlayer) {
                MiniPlayer.LEFT -> R.string.mini_player_left
                MiniPlayer.RIGHT -> R.string.mini_player_right
                MiniPlayer.OFF -> R.string.off
            },
        ]
        MenuEntry.REMOTE -> onOff(s.remoteControl, t)
        MenuEntry.UP_DOWN -> t[if (s.volumeKeys) R.string.up_down_volume else R.string.up_down_vista]
        MenuEntry.SPOKEN -> t[if (s.spokenSkip) R.string.spoken_jump else R.string.spoken_change]
        MenuEntry.SLEEP_TIMER -> sleepValue(ctx.sleep, ctx.kind, t)
        MenuEntry.SLEEP_AFTER -> duration(s.sleepAfterMinutes, t)
        MenuEntry.SLEEP_ACTION -> t[if (s.sleepAction == SleepAction.CLOSE) R.string.sleep_action_close else R.string.sleep_action_release]
        MenuEntry.CONNECTION -> t[
            when {
                !s.isConfigured -> R.string.set_up
                ctx.link == LinkState.CONNECTED -> R.string.connected
                ctx.link == LinkState.AUTH_FAILED -> R.string.link_rejected
                else -> R.string.connecting
            },
        ]
        MenuEntry.MUSIC_ASSISTANT -> t[
            when {
                !s.hasMusicAssistant -> R.string.ma_not_set_up
                ctx.ma == MaLink.CONNECTED -> R.string.connected
                ctx.ma == MaLink.AUTH_FAILED -> R.string.ma_rejected
                ctx.ma == MaLink.UNREACHABLE -> R.string.ma_unreachable
                else -> R.string.connecting
            },
        ]
        MenuEntry.LANGUAGE -> languageValue(ctx.language, t)
    }

    fun description(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts): String = when (e) {
        MenuEntry.VIEW -> t[if (s.view == StageView.FLOW) R.string.about_view_flow else R.string.about_view_single]
        MenuEntry.APPEARANCE -> t[
            when (s.appearance) {
                AppearanceMode.AUTO -> R.string.about_appearance_auto
                AppearanceMode.LIGHT -> R.string.about_appearance_light
                AppearanceMode.DARK -> R.string.about_appearance_dark
            },
        ]
        MenuEntry.CLOCK -> t[R.string.about_clock]
        MenuEntry.TIME_LINE -> t[R.string.about_progress]
        MenuEntry.NEXT -> t[R.string.about_next]
        MenuEntry.ALBUM -> t[R.string.about_album]
        MenuEntry.GROUP -> t[R.string.about_group]
        MenuEntry.VOLUME -> t[
            when (s.volumeDisplay) {
                VolumeDisplay.ON_CHANGE -> R.string.about_volume_on_change
                VolumeDisplay.ALWAYS -> R.string.about_volume_always
                VolumeDisplay.OFF -> R.string.about_volume_off
            },
        ]
        MenuEntry.VALUES -> t[
            when {
                s.ownValues.isEmpty() -> R.string.about_values_set_up
                s.showValues -> R.string.about_values_on
                else -> R.string.about_values_off
            },
        ]
        MenuEntry.BACKGROUND -> t[if (s.background == BackgroundMode.COVER) R.string.about_background_cover else R.string.about_background_plain]
        MenuEntry.LIGHT -> intensity(s, ctx, t)
        MenuEntry.MOTION -> {
            val what = when {
                s.background == BackgroundMode.COVER && s.roomLights.isSet -> R.string.about_motion_both
                s.roomLights.isSet -> R.string.about_motion_lights
                else -> R.string.about_motion_screen
            }
            val how = when (s.motion) {
                MotionLevel.OFF -> R.string.about_motion_off
                MotionLevel.GENTLE -> R.string.about_motion_slow
                MotionLevel.MEDIUM -> R.string.about_motion_medium
                MotionLevel.LIVELY -> R.string.about_motion_fast
            }
            sentences(t[what], t[how], t[R.string.about_motion_playing])
        }
        MenuEntry.LIGHTS -> t[
            when {
                !s.roomLights.isSet -> R.string.about_lights_set_up
                s.roomLightLevel == RoomLightLevel.OFF -> R.string.about_lights_off
                else -> R.string.about_lights_on
            },
        ]
        MenuEntry.LIGHTS_ONLY_ON -> t[
            when {
                !s.roomLights.isSet -> R.string.about_lights_set_up
                s.lightsOnlyOn -> R.string.about_lights_only_on
                else -> R.string.about_lights_switch_on
            },
        ]
        MenuEntry.VISTA_PAUSE -> t[if (s.vistaPauseSeconds == 0) R.string.about_vista_pause_off else R.string.about_vista_pause]
        MenuEntry.VISTA_PLAYING -> t[
            when {
                s.vistaPlayingSeconds != 0 -> R.string.about_vista_playing
                s.volumeKeys -> R.string.about_vista_playing_off_volume
                else -> R.string.about_vista_playing_off
            },
        ]
        MenuEntry.MINI_PLAYER -> t[
            when (s.miniPlayer) {
                MiniPlayer.LEFT -> R.string.about_mini_player_left
                MiniPlayer.RIGHT -> R.string.about_mini_player_right
                MiniPlayer.OFF -> R.string.about_mini_player_off
            },
        ]
        MenuEntry.REMOTE -> t[if (s.remoteControl) R.string.about_remote_on else R.string.about_remote_off]
        MenuEntry.UP_DOWN -> t[if (s.volumeKeys) R.string.about_up_down_volume else R.string.about_up_down_vista]
        MenuEntry.SPOKEN -> t[if (s.spokenSkip) R.string.about_spoken_jump else R.string.about_spoken_change]
        MenuEntry.SLEEP_TIMER -> when {
            ctx.sleep == SleepChoice.OFF -> t[R.string.about_sleep_timer_off]
            ctx.sleepAt != null -> t.format(R.string.about_sleep_timer_at, t.clock(ctx.sleepAt))
            else -> t.format(R.string.about_sleep_timer_end, t[endPhrase(ctx.sleep, ctx.kind)])
        }
        MenuEntry.SLEEP_AFTER -> t[R.string.about_sleep_after]
        MenuEntry.SLEEP_ACTION -> t[if (s.sleepAction == SleepAction.CLOSE) R.string.about_sleep_action_close else R.string.about_sleep_action_release]
        MenuEntry.CONNECTION -> t[if (!s.isConfigured) R.string.about_connection_set_up else R.string.about_connection]
        MenuEntry.MUSIC_ASSISTANT -> t[if (!s.hasMusicAssistant) R.string.about_ma_set_up else R.string.about_ma]
        MenuEntry.LANGUAGE -> t[R.string.about_language]
    }

    /** What "Intensity" does: with the cover's colors how strong they are, else how the cover lights the wall. */
    private fun intensity(s: Settings, ctx: MenuContext, t: Texts): String {
        val (what, how) = when {
            s.background == BackgroundMode.COVER -> R.string.about_intensity_cover to when (s.light) {
                LightLevel.STRONG -> R.string.about_intensity_cover_strong
                LightLevel.NORMAL -> R.string.about_intensity_cover_normal
                LightLevel.SUBTLE -> R.string.about_intensity_cover_low
                LightLevel.OFF -> if (ctx.appearance == Appearance.LIGHT) R.string.about_intensity_cover_off_light else R.string.about_intensity_cover_off_dark
            }
            ctx.appearance == Appearance.LIGHT -> R.string.about_intensity_light to when (s.light) {
                LightLevel.STRONG -> R.string.about_intensity_light_strong
                LightLevel.NORMAL -> R.string.about_intensity_light_normal
                LightLevel.SUBTLE -> R.string.about_intensity_light_low
                LightLevel.OFF -> R.string.about_intensity_light_off
            }
            else -> R.string.about_intensity_dark to when (s.light) {
                LightLevel.STRONG -> R.string.about_intensity_dark_strong
                LightLevel.NORMAL -> R.string.about_intensity_dark_normal
                LightLevel.SUBTLE -> R.string.about_intensity_dark_low
                LightLevel.OFF -> R.string.about_intensity_dark_off
            }
        }
        return sentences(t[what], t[how])
    }

    /**
     * The settings after one step of [e]: [direction] -1 (left) or +1 (right, OK). Left and
     * right stop at the ends of a scale, OK ([wrap]) goes round. Switches simply flip. Null for
     * the lines that are no setting (the connections, the sleep timer, the language) and for the
     * lights' lines and the values while none are set up (OK sets them up).
     */
    fun step(e: MenuEntry, s: Settings, direction: Int, wrap: Boolean = false): Settings? = when (e) {
        MenuEntry.VIEW -> s.copy(view = if (s.view == StageView.FLOW) StageView.SINGLE else StageView.FLOW)
        MenuEntry.CLOCK -> s.copy(showClock = !s.showClock)
        MenuEntry.TIME_LINE -> s.copy(showTimeLine = !s.showTimeLine)
        MenuEntry.NEXT -> s.copy(showNext = !s.showNext)
        MenuEntry.ALBUM -> s.copy(showAlbum = !s.showAlbum)
        MenuEntry.GROUP -> s.copy(showGroup = !s.showGroup)
        MenuEntry.VOLUME -> s.copy(volumeDisplay = move(VOLUME_ORDER, s.volumeDisplay, direction, wrap))
        MenuEntry.VALUES -> if (s.ownValues.isEmpty()) null else s.copy(showValues = !s.showValues)
        MenuEntry.BACKGROUND -> s.copy(background = if (s.background == BackgroundMode.COVER) BackgroundMode.WALL else BackgroundMode.COVER)
        MenuEntry.MOTION -> s.copy(motion = move(MOTION_ORDER, s.motion, direction, wrap))
        MenuEntry.LIGHTS -> if (s.roomLights.isSet) s.copy(roomLightLevel = move(LIGHTS_ORDER, s.roomLightLevel, direction, wrap)) else null
        MenuEntry.LIGHTS_ONLY_ON -> if (s.roomLights.isSet) s.copy(lightsOnlyOn = !s.lightsOnlyOn) else null
        MenuEntry.APPEARANCE -> s.copy(appearance = move(APPEARANCE_ORDER, s.appearance, direction, wrap))
        MenuEntry.LIGHT -> s.copy(light = move(LIGHT_ORDER, s.light, direction, wrap))
        MenuEntry.VISTA_PAUSE -> s.copy(vistaPauseSeconds = move(PAUSE_DELAYS, nearest(PAUSE_DELAYS, s.vistaPauseSeconds), direction, wrap))
        MenuEntry.VISTA_PLAYING -> s.copy(vistaPlayingSeconds = move(PLAYING_DELAYS, nearest(PLAYING_DELAYS, s.vistaPlayingSeconds), direction, wrap))
        MenuEntry.MINI_PLAYER -> s.copy(miniPlayer = move(MINI_ORDER, s.miniPlayer, direction, wrap))
        MenuEntry.REMOTE -> s.copy(remoteControl = !s.remoteControl)
        MenuEntry.UP_DOWN -> s.copy(volumeKeys = !s.volumeKeys)
        MenuEntry.SPOKEN -> s.copy(spokenSkip = !s.spokenSkip)
        MenuEntry.SLEEP_AFTER -> s.copy(sleepAfterMinutes = nextSleep(s.sleepAfterMinutes, direction, wrap))
        MenuEntry.SLEEP_ACTION -> s.copy(
            sleepAction = if (s.sleepAction == SleepAction.CLOSE) SleepAction.RELEASE else SleepAction.CLOSE,
        )
        MenuEntry.CONNECTION, MenuEntry.MUSIC_ASSISTANT, MenuEntry.SLEEP_TIMER, MenuEntry.LANGUAGE -> null
    }

    /** The language after one step from [language] ([direction] -1 or +1; [wrap] goes round). */
    fun stepLanguage(language: AppLanguage, direction: Int, wrap: Boolean): AppLanguage = move(LANGUAGE_ORDER, language, direction, wrap)

    /** The delays offered for the window view (s): when paused, and while music plays; 0 is off. */
    val PAUSE_DELAYS = listOf(0, 5, 15, 30, 60, 180, 300, 600)
    val PLAYING_DELAYS = listOf(0, 60, 120, 300, 600, 900, 1800)

    /** "Off", "Five seconds", "15 seconds", "Three minutes" and so on. */
    fun delay(seconds: Int, t: Texts): String = when {
        seconds <= 0 -> t[R.string.off]
        seconds < 60 -> t.plural(R.plurals.seconds, seconds, t.countWord(seconds))
        else -> duration(seconds / 60, t)
    }

    /** "Ten minutes", "One hour", "90 minutes", "Two hours": numbers up to twelve in words. */
    fun duration(minutes: Int, t: Texts): String {
        val hours = minutes / 60
        return if (minutes % 60 == 0 && hours in 1..MAX_WORD_COUNT) {
            t.plural(R.plurals.hours, hours, t.countWord(hours))
        } else {
            t.plural(R.plurals.minutes, minutes, t.countWord(minutes))
        }
    }

    /** The largest number written in words. */
    private const val MAX_WORD_COUNT = 12

    /** The offered value nearest to [v] (which may have been set from outside). */
    private fun nearest(choices: List<Int>, v: Int): Int = choices.minByOrNull { kotlin.math.abs(it - v) } ?: choices.first()

    /** "Off", "30 minutes", "1 hour", "End of chapter", "End of episode" and so on. */
    fun sleepValue(choice: SleepChoice, kind: MediaKind?, t: Texts): String = when (choice) {
        SleepChoice.OFF -> t[R.string.off]
        SleepChoice.CHAPTER_END -> t[R.string.sleep_chapter_end]
        SleepChoice.ITEM_END -> t[
            when (kind) {
                MediaKind.PODCAST -> R.string.sleep_episode_end
                MediaKind.AUDIOBOOK -> R.string.sleep_book_end
                else -> R.string.sleep_track_end
            },
        ]
        else -> duration(choice.minutes, t)
    }

    /** "end of the chapter", for the sentence that says when the music pauses. */
    @StringRes
    private fun endPhrase(choice: SleepChoice, kind: MediaKind?): Int = when {
        choice == SleepChoice.CHAPTER_END -> R.string.sleep_end_chapter
        kind == MediaKind.PODCAST -> R.string.sleep_end_episode
        kind == MediaKind.AUDIOBOOK -> R.string.sleep_end_book
        else -> R.string.sleep_end_track
    }

    /** A language as the menu names it: English and German in their own words, the system's in the language shown. */
    fun languageValue(language: AppLanguage, t: Texts): String = t[
        when (language) {
            AppLanguage.SYSTEM -> R.string.language_system
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.GERMAN -> R.string.language_german
        },
    ]

    /** From quiet to present: left means less, right means more. */
    private val VOLUME_ORDER = listOf(VolumeDisplay.OFF, VolumeDisplay.ON_CHANGE, VolumeDisplay.ALWAYS)
    private val LIGHT_ORDER = listOf(LightLevel.OFF, LightLevel.SUBTLE, LightLevel.NORMAL, LightLevel.STRONG)
    private val APPEARANCE_ORDER = listOf(AppearanceMode.DARK, AppearanceMode.AUTO, AppearanceMode.LIGHT)
    private val MOTION_ORDER = listOf(MotionLevel.OFF, MotionLevel.GENTLE, MotionLevel.MEDIUM, MotionLevel.LIVELY)
    private val LIGHTS_ORDER = RoomLightLevel.entries
    private val MINI_ORDER = listOf(MiniPlayer.OFF, MiniPlayer.LEFT, MiniPlayer.RIGHT)
    private val LANGUAGE_ORDER = listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.GERMAN)

    private fun <T> move(order: List<T>, current: T, direction: Int, wrap: Boolean): T {
        val i = order.indexOf(current).coerceAtLeast(0) + direction
        return if (wrap) order[Math.floorMod(i, order.size)] else order[i.coerceIn(0, order.size - 1)]
    }

    /** The next offered value from [minutes] (which may be any value set from outside). */
    fun nextSleep(minutes: Int, direction: Int, wrap: Boolean = false): Int {
        val choices = sleepChoices
        return if (direction > 0) {
            choices.firstOrNull { it > minutes } ?: if (wrap) choices.first() else choices.last()
        } else {
            choices.lastOrNull { it < minutes } ?: if (wrap) choices.last() else choices.first()
        }
    }

    private fun onOff(on: Boolean, t: Texts) = t[if (on) R.string.on else R.string.off]

    /** Sentences one after the other. */
    private fun sentences(vararg s: String) = s.joinToString(" ")

    const val HOME_ASSISTANT = "Home Assistant"
    const val MUSIC_ASSISTANT = "Music Assistant"
}
