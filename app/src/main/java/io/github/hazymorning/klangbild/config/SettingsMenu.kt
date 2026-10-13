package io.github.hazymorning.klangbild.config

import androidx.annotation.StringRes
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Texts

/** The groups of the settings, in order: the menu's first level. [about] says what is in one. */
enum class MenuSection(@StringRes val title: Int, @StringRes val about: Int) {
    PICTURE(R.string.section_picture, R.string.about_section_picture),
    INFO(R.string.section_info, R.string.about_section_info),
    LIGHTS(R.string.section_lights, R.string.about_section_lights),
    LANDSCAPE(R.string.section_landscape, R.string.about_section_landscape),
    REMOTE(R.string.section_remote, R.string.about_section_remote),
    TIMER(R.string.section_timer, R.string.about_section_timer),
    SYSTEM(R.string.section_system, R.string.about_section_system),
}

/** One setting, in its group's order; [label] is 0 for a line named after what it connects to. */
enum class MenuEntry(val section: MenuSection, @StringRes val label: Int) {
    VIEW(MenuSection.PICTURE, R.string.entry_view),
    APPEARANCE(MenuSection.PICTURE, R.string.entry_appearance),
    BACKGROUND(MenuSection.PICTURE, R.string.entry_background),
    INTENSITY(MenuSection.PICTURE, R.string.entry_intensity),
    MOTION(MenuSection.PICTURE, R.string.entry_motion),
    CLOCK(MenuSection.INFO, R.string.entry_clock),
    PROGRESS_BAR(MenuSection.INFO, R.string.entry_progress),
    NEXT(MenuSection.INFO, R.string.entry_next),
    ALBUM(MenuSection.INFO, R.string.entry_album),
    GROUP(MenuSection.INFO, R.string.entry_group),
    VOLUME(MenuSection.INFO, R.string.entry_volume),
    VALUES(MenuSection.INFO, R.string.entry_values),
    LYRICS(MenuSection.INFO, R.string.entry_lyrics),
    BRIGHTNESS(MenuSection.LIGHTS, R.string.entry_brightness),
    OFF_LIGHTS(MenuSection.LIGHTS, R.string.entry_off_lights),
    LANDSCAPE_PAUSE(MenuSection.LANDSCAPE, R.string.entry_landscape_pause),
    LANDSCAPE_PLAYING(MenuSection.LANDSCAPE, R.string.entry_landscape_playing),
    MINI_PLAYER(MenuSection.LANDSCAPE, R.string.entry_mini_player),
    REMOTE(MenuSection.REMOTE, R.string.entry_remote),
    UP_DOWN(MenuSection.REMOTE, R.string.entry_up_down),
    SPOKEN(MenuSection.REMOTE, R.string.entry_spoken),
    SLEEP_TIMER(MenuSection.TIMER, R.string.entry_sleep_timer),
    SLEEP_AFTER(MenuSection.TIMER, R.string.entry_sleep_after),
    SLEEP_ACTION(MenuSection.TIMER, R.string.entry_sleep_action),
    CONNECTION(MenuSection.SYSTEM, 0),
    MUSIC_ASSISTANT(MenuSection.SYSTEM, 0),
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
 * A line as the menu shows it: a setting ([entry]) with its label, the value in words and what
 * the option does, or a group of settings ([section]) with what is in it. [level] (0 to 1) for a
 * value shown on a slider. A [dimmed] setting cannot work yet: its value says what it needs, and
 * OK sets that up.
 */
data class MenuRow(
    val entry: MenuEntry?,
    val label: String,
    val value: String,
    val description: String,
    val level: Float? = null,
    val section: MenuSection? = null,
    val dimmed: Boolean = false,
)

/**
 * The settings menu, operated with the remote, in two levels: first the groups, then the
 * settings of the one opened. Up and down choose a line, OK (or right) opens a group, left and
 * right (or OK) change a setting at once; there is nothing to confirm. Back goes up a level.
 * Every text comes from the resources, in the language shown.
 */
object SettingsMenu {
    val entries: List<MenuEntry> = MenuEntry.entries

    val sections: List<MenuSection> = MenuSection.entries

    /** The settings of [section], in order. */
    fun entriesOf(section: MenuSection): List<MenuEntry> = entries.filter { it.section == section }

    /** Minutes offered for the sleep timeout. */
    val sleepChoices = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

    /** The lines of [section]'s settings. */
    fun rows(s: Settings, ctx: MenuContext, t: Texts, section: MenuSection): List<MenuRow> = entriesOf(section).map { row(it, s, ctx, t) }

    /** The menu's first level: a line for each group, saying what is in it. */
    fun groups(t: Texts): List<MenuRow> = sections.map { MenuRow(null, t[it.title], "", t[it.about], section = it) }

    private fun row(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts) =
        MenuRow(e, label(e, t), value(e, s, ctx, t), description(e, s, ctx, t), level(e, s), dimmed = needsSetup(e, s))

    /**
     * Whether [e] cannot work until something is set up on the phone: lyrics without Music
     * Assistant, the lights and the values while none are chosen. OK opens the setup.
     */
    fun needsSetup(e: MenuEntry, s: Settings): Boolean = when (e) {
        MenuEntry.LYRICS -> !s.hasMusicAssistant
        MenuEntry.BRIGHTNESS, MenuEntry.OFF_LIGHTS -> !s.roomLights.isSet
        MenuEntry.VALUES -> s.ownValues.isEmpty()
        else -> false
    }

    /** A line's label; the two connection lines are named after what they connect to. */
    fun label(e: MenuEntry, t: Texts): String = when (e) {
        MenuEntry.CONNECTION -> HOME_ASSISTANT
        MenuEntry.MUSIC_ASSISTANT -> MUSIC_ASSISTANT
        else -> t[e.label]
    }

    /** Where a slider stands: the lights' brightness. */
    fun level(e: MenuEntry, s: Settings): Float? = when (e) {
        MenuEntry.BRIGHTNESS -> s.roomLightBrightness.brightness.takeIf { s.roomLights.isSet }
        else -> null
    }

    fun value(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts): String = when (e) {
        MenuEntry.VIEW -> t[if (s.view == StageView.FLOW) R.string.view_flow else R.string.view_single]
        MenuEntry.CLOCK -> onOff(s.showClock, t)
        MenuEntry.PROGRESS_BAR -> onOff(s.showProgressBar, t)
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
        MenuEntry.LYRICS -> if (!s.hasMusicAssistant) t[R.string.needs_music_assistant] else onOff(s.lyrics, t)
        MenuEntry.BACKGROUND -> t[if (s.background == BackgroundMode.COVER) R.string.background_cover else R.string.background_plain]
        MenuEntry.MOTION -> t[
            when (s.motion) {
                MotionLevel.OFF -> R.string.off
                MotionLevel.GENTLE -> R.string.motion_slow
                MotionLevel.MEDIUM -> R.string.motion_medium
                MotionLevel.LIVELY -> R.string.motion_fast
            },
        ]
        MenuEntry.BRIGHTNESS -> when {
            !s.roomLights.isSet -> t[R.string.set_up]
            s.roomLightBrightness == RoomLightBrightness.OFF -> t[R.string.off]
            else -> t.percent(s.roomLightBrightness.percent)
        }
        MenuEntry.OFF_LIGHTS -> t[
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
        MenuEntry.INTENSITY -> t[
            when (s.intensity) {
                Intensity.STRONG -> R.string.intensity_strong
                Intensity.NORMAL -> R.string.intensity_normal
                Intensity.SUBTLE -> R.string.intensity_low
                Intensity.OFF -> R.string.off
            },
        ]
        MenuEntry.LANDSCAPE_PAUSE -> delay(s.landscapePauseSeconds, t)
        MenuEntry.LANDSCAPE_PLAYING -> delay(s.landscapePlayingSeconds, t)
        MenuEntry.MINI_PLAYER -> t[
            when (s.miniPlayer) {
                MiniPlayer.LEFT -> R.string.mini_player_left
                MiniPlayer.RIGHT -> R.string.mini_player_right
                MiniPlayer.OFF -> R.string.off
            },
        ]
        MenuEntry.REMOTE -> onOff(s.remoteControl, t)
        MenuEntry.UP_DOWN -> t[if (s.volumeKeys) R.string.up_down_volume else R.string.up_down_landscape]
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
        MenuEntry.PROGRESS_BAR -> t[R.string.about_progress]
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
        MenuEntry.LYRICS -> t[
            when {
                !s.hasMusicAssistant -> R.string.about_lyrics_set_up
                s.lyrics -> R.string.about_lyrics_on
                else -> R.string.about_lyrics_off
            },
        ]
        MenuEntry.BACKGROUND -> t[if (s.background == BackgroundMode.COVER) R.string.about_background_cover else R.string.about_background_plain]
        MenuEntry.INTENSITY -> intensity(s, ctx, t)
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
        MenuEntry.BRIGHTNESS -> t[
            when {
                !s.roomLights.isSet -> R.string.about_lights_set_up
                s.roomLightBrightness == RoomLightBrightness.OFF -> R.string.about_lights_off
                else -> R.string.about_lights_on
            },
        ]
        MenuEntry.OFF_LIGHTS -> t[
            when {
                !s.roomLights.isSet -> R.string.about_lights_set_up
                s.lightsOnlyOn -> R.string.about_lights_only_on
                else -> R.string.about_lights_switch_on
            },
        ]
        MenuEntry.LANDSCAPE_PAUSE -> t[if (s.landscapePauseSeconds == 0) R.string.about_landscape_pause_off else R.string.about_landscape_pause]
        MenuEntry.LANDSCAPE_PLAYING -> t[
            when {
                s.landscapePlayingSeconds != 0 -> R.string.about_landscape_playing
                s.volumeKeys -> R.string.about_landscape_playing_off_volume
                else -> R.string.about_landscape_playing_off
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
        MenuEntry.UP_DOWN -> t[if (s.volumeKeys) R.string.about_up_down_volume else R.string.about_up_down_landscape]
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
            s.background == BackgroundMode.COVER -> R.string.about_intensity_cover to when (s.intensity) {
                Intensity.STRONG -> R.string.about_intensity_cover_strong
                Intensity.NORMAL -> R.string.about_intensity_cover_normal
                Intensity.SUBTLE -> R.string.about_intensity_cover_low
                Intensity.OFF -> if (ctx.appearance == Appearance.LIGHT) R.string.about_intensity_cover_off_light else R.string.about_intensity_cover_off_dark
            }
            ctx.appearance == Appearance.LIGHT -> R.string.about_intensity_light to when (s.intensity) {
                Intensity.STRONG -> R.string.about_intensity_light_strong
                Intensity.NORMAL -> R.string.about_intensity_light_normal
                Intensity.SUBTLE -> R.string.about_intensity_light_low
                Intensity.OFF -> R.string.about_intensity_light_off
            }
            else -> R.string.about_intensity_dark to when (s.intensity) {
                Intensity.STRONG -> R.string.about_intensity_dark_strong
                Intensity.NORMAL -> R.string.about_intensity_dark_normal
                Intensity.SUBTLE -> R.string.about_intensity_dark_low
                Intensity.OFF -> R.string.about_intensity_dark_off
            }
        }
        return sentences(t[what], t[how])
    }

    /**
     * The settings after one step of [e]: [direction] -1 (left) or +1 (right, OK). Left and
     * right stop at the ends of a scale, OK ([wrap]) goes round. Switches simply flip. Null for
     * the lines that are no setting (the connections, the sleep timer, the language) and for the
     * lines that need something set up first ([needsSetup]: OK sets it up).
     */
    fun step(e: MenuEntry, s: Settings, direction: Int, wrap: Boolean = false): Settings? = when (e) {
        MenuEntry.VIEW -> s.copy(view = if (s.view == StageView.FLOW) StageView.SINGLE else StageView.FLOW)
        MenuEntry.CLOCK -> s.copy(showClock = !s.showClock)
        MenuEntry.PROGRESS_BAR -> s.copy(showProgressBar = !s.showProgressBar)
        MenuEntry.NEXT -> s.copy(showNext = !s.showNext)
        MenuEntry.ALBUM -> s.copy(showAlbum = !s.showAlbum)
        MenuEntry.GROUP -> s.copy(showGroup = !s.showGroup)
        MenuEntry.VOLUME -> s.copy(volumeDisplay = move(VOLUME_ORDER, s.volumeDisplay, direction, wrap))
        MenuEntry.VALUES -> if (s.ownValues.isEmpty()) null else s.copy(showValues = !s.showValues)
        MenuEntry.LYRICS -> if (!s.hasMusicAssistant) null else s.copy(lyrics = !s.lyrics)
        MenuEntry.BACKGROUND -> s.copy(background = if (s.background == BackgroundMode.COVER) BackgroundMode.WALL else BackgroundMode.COVER)
        MenuEntry.MOTION -> s.copy(motion = move(MOTION_ORDER, s.motion, direction, wrap))
        MenuEntry.BRIGHTNESS -> if (s.roomLights.isSet) s.copy(roomLightBrightness = move(BRIGHTNESS_ORDER, s.roomLightBrightness, direction, wrap)) else null
        MenuEntry.OFF_LIGHTS -> if (s.roomLights.isSet) s.copy(lightsOnlyOn = !s.lightsOnlyOn) else null
        MenuEntry.APPEARANCE -> s.copy(appearance = move(APPEARANCE_ORDER, s.appearance, direction, wrap))
        MenuEntry.INTENSITY -> s.copy(intensity = move(INTENSITY_ORDER, s.intensity, direction, wrap))
        MenuEntry.LANDSCAPE_PAUSE -> s.copy(landscapePauseSeconds = move(PAUSE_DELAYS, nearest(PAUSE_DELAYS, s.landscapePauseSeconds), direction, wrap))
        MenuEntry.LANDSCAPE_PLAYING -> s.copy(landscapePlayingSeconds = move(PLAYING_DELAYS, nearest(PLAYING_DELAYS, s.landscapePlayingSeconds), direction, wrap))
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

    /** The delays offered for the landscape (s): when paused, and while music plays; 0 is off. */
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
    private val INTENSITY_ORDER = listOf(Intensity.OFF, Intensity.SUBTLE, Intensity.NORMAL, Intensity.STRONG)
    private val APPEARANCE_ORDER = listOf(AppearanceMode.DARK, AppearanceMode.AUTO, AppearanceMode.LIGHT)
    private val MOTION_ORDER = listOf(MotionLevel.OFF, MotionLevel.GENTLE, MotionLevel.MEDIUM, MotionLevel.LIVELY)
    private val BRIGHTNESS_ORDER = RoomLightBrightness.entries
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
