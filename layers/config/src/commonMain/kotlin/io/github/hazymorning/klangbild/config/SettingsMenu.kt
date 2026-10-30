package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.UpdateInfo
import io.github.hazymorning.klangbild.domain.UpdateState
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Plurals
import io.github.hazymorning.klangbild.text.StringId
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts

/** The groups of the settings, in order: the menu's first level. [about] says what is in one. */
enum class MenuSection(val title: StringId, val about: StringId) {
    PICTURE(Strings.sectionPicture, Strings.aboutSectionPicture),
    INFO(Strings.sectionInfo, Strings.aboutSectionInfo),
    LIGHTS(Strings.sectionLights, Strings.aboutSectionLights),
    REMOTE(Strings.sectionRemote, Strings.aboutSectionRemote),
    TIMER(Strings.sectionTimer, Strings.aboutSectionTimer),
    SYSTEM(Strings.sectionSystem, Strings.aboutSectionSystem),
}

/** One setting, in its group's order; [label] is null for a line named after what it connects to. */
enum class MenuEntry(val section: MenuSection, val label: StringId?) {
    QUEUE(MenuSection.PICTURE, Strings.entryQueue),
    APPEARANCE(MenuSection.PICTURE, Strings.entryAppearance),
    BACKGROUND(MenuSection.PICTURE, Strings.entryBackground),
    INTENSITY(MenuSection.PICTURE, Strings.entryIntensity),
    MOTION(MenuSection.PICTURE, Strings.entryMotion),
    CLOCK(MenuSection.INFO, Strings.entryClock),
    PROGRESS_BAR(MenuSection.INFO, Strings.entryProgress),
    NEXT(MenuSection.INFO, Strings.entryNext),
    ALBUM(MenuSection.INFO, Strings.entryAlbum),
    GROUP(MenuSection.INFO, Strings.entryGroup),
    VOLUME(MenuSection.INFO, Strings.entryVolume),
    VALUES(MenuSection.INFO, Strings.entryValues),
    LYRICS(MenuSection.INFO, Strings.entryLyrics),
    BRIGHTNESS(MenuSection.LIGHTS, Strings.entryBrightness),
    OFF_LIGHTS(MenuSection.LIGHTS, Strings.entryOffLights),
    REMOTE(MenuSection.REMOTE, Strings.entryRemote),
    UP_DOWN(MenuSection.REMOTE, Strings.entryUpDown),
    SPOKEN(MenuSection.REMOTE, Strings.entrySpoken),
    SLEEP_TIMER(MenuSection.TIMER, Strings.entrySleepTimer),
    SLEEP_AFTER(MenuSection.TIMER, Strings.entrySleepAfter),
    SLEEP_ACTION(MenuSection.TIMER, Strings.entrySleepAction),
    SCREEN_OFF(MenuSection.TIMER, Strings.entryScreenOff),
    CONNECTION(MenuSection.SYSTEM, null),
    MUSIC_ASSISTANT(MenuSection.SYSTEM, null),
    SPEAKER(MenuSection.SYSTEM, Strings.entrySpeaker),
    OTHER_ROOMS(MenuSection.SYSTEM, Strings.entryOtherRooms),
    PLAY_HERE(MenuSection.SYSTEM, Strings.entryPlayHere),
    LANGUAGE(MenuSection.SYSTEM, Strings.entryLanguage),
    UPDATE(MenuSection.SYSTEM, Strings.entryUpdate),
    UPDATE_CHECK(MenuSection.SYSTEM, Strings.entryUpdateCheck),
}

/**
 * What the menu needs to know besides the settings: the connections and the wall as they are
 * now, the sleep timer ([sleep], pausing at [sleepAt]), what kind of thing plays, the language
 * chosen, how this TV stands as a speaker ([speaker]), and Klangbild's own new versions.
 */
data class MenuContext(
    val link: LinkState? = null,
    val appearance: Appearance = Appearance.DARK,
    val ma: MaLink? = null,
    val sleep: SleepChoice = SleepChoice.OFF,
    val sleepAt: Long? = null,
    val kind: MediaKind? = null,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val speaker: SpeakerLink = SpeakerLink.OFF,
    /** The name of the speaker followed, once Home Assistant has told it. */
    val speakerName: String? = null,
    val update: UpdateInfo = UpdateInfo(),
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

    /** The menu's first level: a line for each group, saying what is in it; System tells of a new version. */
    fun groups(s: Settings, ctx: MenuContext, t: Texts): List<MenuRow> = sections.map {
        val value = if (it == MenuSection.SYSTEM && s.updateCheck && ctx.update.available != null) t[Strings.updateNotice] else ""
        MenuRow(null, t[it.title], value, t[it.about], section = it)
    }

    private fun row(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts) =
        MenuRow(e, label(e, t), value(e, s, ctx, t), description(e, s, ctx, t), level(e, s), dimmed = needsSetup(e, s))

    /**
     * Whether [e] cannot work until something is set up on the phone: lyrics and playing here
     * without Music Assistant, the lights, the values, the other rooms and the presence sensors
     * while none are chosen. OK opens the setup.
     */
    fun needsSetup(e: MenuEntry, s: Settings): Boolean = when (e) {
        MenuEntry.LYRICS, MenuEntry.SPEAKER, MenuEntry.PLAY_HERE -> !s.hasMusicAssistant
        MenuEntry.BRIGHTNESS, MenuEntry.OFF_LIGHTS -> !s.roomLights.isSet
        MenuEntry.VALUES -> s.ownValues.isEmpty()
        MenuEntry.OTHER_ROOMS -> s.otherRooms.isEmpty()
        MenuEntry.SCREEN_OFF -> s.presenceSensors.isEmpty()
        else -> false
    }

    /**
     * Whether Klangbild follows this TV's own speaker ("This TV"): then it keeps playing here, and
     * leaving it goes back to the speaker followed before, or, without one, to the phone.
     */
    fun followsThisTv(s: Settings): Boolean = s.entityId == EntityId.THIS_TV && s.tvSpeaker

    /**
     * Whether a step of [e] ([direction] and [wrap] as in [step]) opens the setup: what needs
     * setting up first, and leaving this TV while no other speaker was ever followed, by Speaker
     * or by turning playing here off.
     */
    fun opensSetup(e: MenuEntry, s: Settings, direction: Int, wrap: Boolean): Boolean {
        if (needsSetup(e, s)) return true
        val leavesThisTv = when (e) {
            MenuEntry.SPEAKER -> true
            MenuEntry.PLAY_HERE -> move(PLAY_HERE_ORDER, s.playHere, direction, wrap) == PlayHere.OFF
            else -> false
        }
        return leavesThisTv && followsThisTv(s) && s.otherSpeaker == null
    }

    /** A line's label; the two connection lines are named after what they connect to. */
    fun label(e: MenuEntry, t: Texts): String = when (e) {
        MenuEntry.CONNECTION -> HOME_ASSISTANT
        MenuEntry.MUSIC_ASSISTANT -> MUSIC_ASSISTANT
        else -> t[checkNotNull(e.label)]
    }

    /** Where a slider stands: the lights' brightness. */
    fun level(e: MenuEntry, s: Settings): Float? = when (e) {
        MenuEntry.BRIGHTNESS -> s.roomLightBrightness.brightness.takeIf { s.roomLights.isSet }
        else -> null
    }

    fun value(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts): String = when (e) {
        MenuEntry.QUEUE -> queueValue(s.queue, t)
        MenuEntry.CLOCK -> onOff(s.showClock, t)
        MenuEntry.PROGRESS_BAR -> onOff(s.showProgressBar, t)
        MenuEntry.NEXT -> onOff(s.showNext, t)
        MenuEntry.ALBUM -> onOff(s.showAlbum, t)
        MenuEntry.GROUP -> onOff(s.showGroup, t)
        MenuEntry.VOLUME -> t[
            when (s.volumeDisplay) {
                VolumeDisplay.ON_CHANGE -> Strings.volumeOnChange
                VolumeDisplay.ALWAYS -> Strings.volumeAlways
                VolumeDisplay.OFF -> Strings.off
            },
        ]
        MenuEntry.VALUES -> if (s.ownValues.isEmpty()) t[Strings.setUp] else onOff(s.showValues, t)
        MenuEntry.LYRICS -> if (!s.hasMusicAssistant) t[Strings.needsMusicAssistant] else onOff(s.lyrics, t)
        MenuEntry.BACKGROUND -> t[if (s.background == BackgroundMode.COVER) Strings.backgroundCover else Strings.backgroundPlain]
        MenuEntry.MOTION -> t[
            when (s.motion) {
                MotionLevel.OFF -> Strings.off
                MotionLevel.GENTLE -> Strings.motionSlow
                MotionLevel.MEDIUM -> Strings.motionMedium
                MotionLevel.LIVELY -> Strings.motionFast
            },
        ]
        MenuEntry.BRIGHTNESS -> when {
            !s.roomLights.isSet -> t[Strings.setUp]
            s.roomLightBrightness == RoomLightBrightness.OFF -> t[Strings.off]
            else -> t.percent(s.roomLightBrightness.percent)
        }
        MenuEntry.OFF_LIGHTS -> t[
            when {
                !s.roomLights.isSet -> Strings.setUp
                s.lightsOnlyOn -> Strings.offLightsLeave
                else -> Strings.offLightsTurnOn
            },
        ]
        MenuEntry.APPEARANCE -> t[
            when (s.appearance) {
                AppearanceMode.AUTO -> Strings.appearanceAuto
                AppearanceMode.LIGHT -> Strings.appearanceLight
                AppearanceMode.DARK -> Strings.appearanceDark
            },
        ]
        MenuEntry.INTENSITY -> t[
            when (s.intensity) {
                Intensity.STRONG -> Strings.intensityStrong
                Intensity.NORMAL -> Strings.intensityNormal
                Intensity.SUBTLE -> Strings.intensityLow
                Intensity.OFF -> Strings.off
            },
        ]
        MenuEntry.REMOTE -> onOff(s.remoteControl, t)
        MenuEntry.UP_DOWN -> t[if (s.volumeKeys) Strings.upDownVolume else Strings.upDownSettings]
        MenuEntry.SPOKEN -> t[if (s.spokenSkip) Strings.spokenJump else Strings.spokenChange]
        MenuEntry.SLEEP_TIMER -> sleepValue(ctx.sleep, ctx.kind, t)
        MenuEntry.SLEEP_AFTER -> duration(s.sleepAfterMinutes, t)
        MenuEntry.SLEEP_ACTION -> t[if (s.sleepAction == SleepAction.CLOSE) Strings.sleepActionClose else Strings.sleepActionRelease]
        MenuEntry.SCREEN_OFF -> t[
            when {
                s.presenceSensors.isEmpty() -> Strings.setUp
                s.screenOff -> Strings.screenOffNobody
                else -> Strings.screenOffNever
            },
        ]
        MenuEntry.CONNECTION -> t[
            when {
                !s.isConfigured -> Strings.setUp
                ctx.link == LinkState.CONNECTED -> Strings.connected
                ctx.link == LinkState.AUTH_FAILED -> Strings.linkRejected
                else -> Strings.connecting
            },
        ]
        MenuEntry.MUSIC_ASSISTANT -> t[
            when {
                !s.hasMusicAssistant -> Strings.maNotSetUp
                ctx.ma == MaLink.CONNECTED -> Strings.connected
                ctx.ma == MaLink.AUTH_FAILED -> Strings.maRejected
                ctx.ma == MaLink.UNREACHABLE -> Strings.maUnreachable
                else -> Strings.connecting
            },
        ]
        MenuEntry.SPEAKER -> when {
            !s.hasMusicAssistant -> t[Strings.needsMusicAssistant]
            s.entityId == EntityId.THIS_TV -> t[Strings.pageThisTv]
            else -> ctx.speakerName ?: s.entityId?.substringAfter('.')?.replace('_', ' ')?.replaceFirstChar { it.uppercaseChar() } ?: t[Strings.setUp]
        }
        MenuEntry.OTHER_ROOMS -> if (s.otherRooms.isEmpty()) t[Strings.setUp] else onOff(s.showOtherRooms, t)
        MenuEntry.PLAY_HERE -> t[
            when {
                !s.hasMusicAssistant -> Strings.needsMusicAssistant
                s.playHere == PlayHere.OPEN -> Strings.playHereOpen
                s.playHere == PlayHere.ALWAYS -> Strings.playHereAlways
                else -> Strings.off
            },
        ]
        MenuEntry.LANGUAGE -> languageValue(ctx.language, t)
        MenuEntry.UPDATE -> updateValue(ctx.update, t)
        MenuEntry.UPDATE_CHECK -> onOff(s.updateCheck, t)
    }

    fun description(e: MenuEntry, s: Settings, ctx: MenuContext, t: Texts): String = when (e) {
        MenuEntry.QUEUE -> t[
            when (s.queue) {
                QueueDisplay.OFF -> Strings.aboutQueueOff
                QueueDisplay.LIST -> if (s.hasMusicAssistant) Strings.aboutQueueList else Strings.aboutQueueListSetUp
                QueueDisplay.FLOW -> Strings.aboutQueueFlow
            },
        ]
        MenuEntry.APPEARANCE -> t[
            when (s.appearance) {
                AppearanceMode.AUTO -> Strings.aboutAppearanceAuto
                AppearanceMode.LIGHT -> Strings.aboutAppearanceLight
                AppearanceMode.DARK -> Strings.aboutAppearanceDark
            },
        ]
        MenuEntry.CLOCK -> t[Strings.aboutClock]
        MenuEntry.PROGRESS_BAR -> t[Strings.aboutProgress]
        MenuEntry.NEXT -> t[Strings.aboutNext]
        MenuEntry.ALBUM -> t[Strings.aboutAlbum]
        MenuEntry.GROUP -> t[Strings.aboutGroup]
        MenuEntry.VOLUME -> t[
            when (s.volumeDisplay) {
                VolumeDisplay.ON_CHANGE -> Strings.aboutVolumeOnChange
                VolumeDisplay.ALWAYS -> Strings.aboutVolumeAlways
                VolumeDisplay.OFF -> Strings.aboutVolumeOff
            },
        ]
        MenuEntry.VALUES -> t[
            when {
                s.ownValues.isEmpty() -> Strings.aboutValuesSetUp
                s.showValues -> Strings.aboutValuesOn
                else -> Strings.aboutValuesOff
            },
        ]
        MenuEntry.LYRICS -> t[
            when {
                !s.hasMusicAssistant -> Strings.aboutLyricsSetUp
                s.lyrics -> Strings.aboutLyricsOn
                else -> Strings.aboutLyricsOff
            },
        ]
        MenuEntry.BACKGROUND -> t[if (s.background == BackgroundMode.COVER) Strings.aboutBackgroundCover else Strings.aboutBackgroundPlain]
        MenuEntry.INTENSITY -> intensity(s, ctx, t)
        MenuEntry.MOTION -> {
            val what = when {
                s.background == BackgroundMode.COVER && s.roomLights.isSet -> Strings.aboutMotionBoth
                s.roomLights.isSet -> Strings.aboutMotionLights
                else -> Strings.aboutMotionScreen
            }
            val how = when (s.motion) {
                MotionLevel.OFF -> Strings.aboutMotionOff
                MotionLevel.GENTLE -> Strings.aboutMotionSlow
                MotionLevel.MEDIUM -> Strings.aboutMotionMedium
                MotionLevel.LIVELY -> Strings.aboutMotionFast
            }
            sentences(t[what], t[how], t[Strings.aboutMotionPlaying])
        }
        MenuEntry.BRIGHTNESS -> t[
            when {
                !s.roomLights.isSet -> Strings.aboutLightsSetUp
                s.roomLightBrightness == RoomLightBrightness.OFF -> Strings.aboutLightsOff
                else -> Strings.aboutLightsOn
            },
        ]
        MenuEntry.OFF_LIGHTS -> t[
            when {
                !s.roomLights.isSet -> Strings.aboutLightsSetUp
                s.lightsOnlyOn -> Strings.aboutLightsOnlyOn
                else -> Strings.aboutLightsSwitchOn
            },
        ]
        MenuEntry.REMOTE -> t[if (s.remoteControl) Strings.aboutRemoteOn else Strings.aboutRemoteOff]
        MenuEntry.UP_DOWN -> t[if (s.volumeKeys) Strings.aboutUpDownVolume else Strings.aboutUpDownSettings]
        MenuEntry.SPOKEN -> t[if (s.spokenSkip) Strings.aboutSpokenJump else Strings.aboutSpokenChange]
        MenuEntry.SLEEP_TIMER -> when {
            ctx.sleep == SleepChoice.OFF -> t[Strings.aboutSleepTimerOff]
            ctx.sleepAt != null -> t.format(Strings.aboutSleepTimerAt, t.clock(ctx.sleepAt))
            else -> t.format(Strings.aboutSleepTimerEnd, t[endPhrase(ctx.sleep, ctx.kind)])
        }
        MenuEntry.SLEEP_AFTER -> t[if (s.darkWhenNobody) Strings.aboutSleepAfterNobody else Strings.aboutSleepAfter]
        MenuEntry.SLEEP_ACTION -> t[if (s.sleepAction == SleepAction.CLOSE) Strings.aboutSleepActionClose else Strings.aboutSleepActionRelease]
        MenuEntry.SCREEN_OFF -> t[
            when {
                s.presenceSensors.isEmpty() -> Strings.aboutScreenOffSetUp
                s.screenOff -> Strings.aboutScreenOffOn
                else -> Strings.aboutScreenOffOff
            },
        ]
        MenuEntry.CONNECTION -> t[if (!s.isConfigured) Strings.aboutConnectionSetUp else Strings.aboutConnection]
        MenuEntry.MUSIC_ASSISTANT -> t[if (!s.hasMusicAssistant) Strings.aboutMaSetUp else Strings.aboutMa]
        MenuEntry.SPEAKER -> t[
            when {
                !s.hasMusicAssistant -> Strings.aboutPlayHereSetUp
                followsThisTv(s) && s.otherSpeaker == null -> Strings.aboutSpeakerThisTv
                else -> Strings.aboutSpeaker
            },
        ]
        MenuEntry.OTHER_ROOMS -> t[
            when {
                s.otherRooms.isEmpty() -> Strings.aboutOtherRoomsSetUp
                s.showOtherRooms -> Strings.aboutOtherRoomsOn
                else -> Strings.aboutOtherRoomsOff
            },
        ]
        MenuEntry.PLAY_HERE -> t[playHere(s, ctx.speaker)]
        MenuEntry.LANGUAGE -> t[Strings.aboutLanguage]
        MenuEntry.UPDATE -> updateAbout(ctx.update, t)
        MenuEntry.UPDATE_CHECK -> t[
            when {
                ctx.update.installed == null -> Strings.aboutUpdateOwn
                s.updateCheck -> Strings.aboutUpdateCheckOn
                else -> Strings.aboutUpdateCheckOff
            },
        ]
    }

    /** Where Klangbild stands with a new version, in a word or two. */
    private fun updateValue(u: UpdateInfo, t: Texts): String {
        val available = u.available
        return when {
            u.installed == null -> u.name.ifEmpty { t[Strings.updateUnknown] }
            u.state == UpdateState.DOWNLOADING -> t[Strings.updateDownloading]
            u.state == UpdateState.FAILED -> t[Strings.updateFailed]
            available != null -> t.format(Strings.updateVersion, available.removePrefix("v"))
            u.state == UpdateState.CHECKING -> t[Strings.updateChecking]
            u.latest == null -> t[Strings.updateUnknown]
            else -> t[Strings.updateCurrent]
        }
    }

    /** What the Update line does now. */
    private fun updateAbout(u: UpdateInfo, t: Texts): String {
        val available = u.available
        return when {
            u.installed == null -> t[Strings.aboutUpdateOwn]
            u.state == UpdateState.DOWNLOADING -> t[Strings.aboutUpdateDownloading]
            u.state == UpdateState.FAILED -> t[Strings.aboutUpdateFailed]
            available != null -> t.format(Strings.aboutUpdateAvailable, available.removePrefix("v"))
            u.latest == null -> t[Strings.aboutUpdateUnknown]
            else -> t.format(Strings.aboutUpdateCurrent, u.name)
        }
    }

    /**
     * What playing here does, or, while it is on, what keeps it from playing: that comes first,
     * also while this TV is the speaker followed.
     */
    private fun playHere(s: Settings, speaker: SpeakerLink): StringId = when {
        !s.hasMusicAssistant -> Strings.aboutPlayHereSetUp
        !s.tvSpeaker -> Strings.aboutPlayHereOff
        else -> when (speaker) {
            SpeakerLink.UNREACHABLE -> Strings.aboutPlayHereUnreachable
            SpeakerLink.TOO_OLD -> Strings.aboutPlayHereTooOld
            SpeakerLink.TOO_NEW -> Strings.aboutPlayHereTooNew
            SpeakerLink.REFUSED -> Strings.aboutPlayHereRefused
            SpeakerLink.NO_KEY -> Strings.aboutPlayHereNoKey
            SpeakerLink.OFF, SpeakerLink.CONNECTING, SpeakerLink.CONNECTED ->
                when {
                    !followsThisTv(s) -> if (s.playHere == PlayHere.ALWAYS) Strings.aboutPlayHereAlways else Strings.aboutPlayHereOpen
                    s.otherSpeaker == null -> Strings.aboutPlayHereFollowed
                    else -> Strings.aboutPlayHereFollowedBack
                }
        }
    }

    /** What "Intensity" does: with the cover's colors how strong they are, else how the cover lights the wall. */
    private fun intensity(s: Settings, ctx: MenuContext, t: Texts): String {
        val (what, how) = when {
            s.background == BackgroundMode.COVER -> Strings.aboutIntensityCover to when (s.intensity) {
                Intensity.STRONG -> Strings.aboutIntensityCoverStrong
                Intensity.NORMAL -> Strings.aboutIntensityCoverNormal
                Intensity.SUBTLE -> Strings.aboutIntensityCoverLow
                Intensity.OFF -> if (ctx.appearance == Appearance.LIGHT) Strings.aboutIntensityCoverOffLight else Strings.aboutIntensityCoverOffDark
            }
            ctx.appearance == Appearance.LIGHT -> Strings.aboutIntensityLight to when (s.intensity) {
                Intensity.STRONG -> Strings.aboutIntensityLightStrong
                Intensity.NORMAL -> Strings.aboutIntensityLightNormal
                Intensity.SUBTLE -> Strings.aboutIntensityLightLow
                Intensity.OFF -> Strings.aboutIntensityLightOff
            }
            else -> Strings.aboutIntensityDark to when (s.intensity) {
                Intensity.STRONG -> Strings.aboutIntensityDarkStrong
                Intensity.NORMAL -> Strings.aboutIntensityDarkNormal
                Intensity.SUBTLE -> Strings.aboutIntensityDarkLow
                Intensity.OFF -> Strings.aboutIntensityDarkOff
            }
        }
        return sentences(t[what], t[how])
    }

    /**
     * The settings after one step of [e]: [direction] -1 (left) or +1 (right, OK). Left and
     * right stop at the ends of a scale, OK ([wrap]) goes round. Switches simply flip. Null for
     * the lines that are no setting (the connections, the sleep timer, the language) and for the
     * lines that need something set up first ([opensSetup]: OK sets it up).
     */
    fun step(e: MenuEntry, s: Settings, direction: Int, wrap: Boolean = false): Settings? = when (e) {
        MenuEntry.QUEUE -> s.copy(queue = move(QUEUE_ORDER, s.queue, direction, wrap))
        MenuEntry.CLOCK -> s.copy(showClock = !s.showClock)
        MenuEntry.PROGRESS_BAR -> s.copy(showProgressBar = !s.showProgressBar)
        MenuEntry.NEXT -> s.copy(showNext = !s.showNext)
        MenuEntry.ALBUM -> s.copy(showAlbum = !s.showAlbum)
        MenuEntry.GROUP -> s.copy(showGroup = !s.showGroup)
        MenuEntry.VOLUME -> s.copy(volumeDisplay = move(VOLUME_ORDER, s.volumeDisplay, direction, wrap))
        MenuEntry.VALUES -> if (s.ownValues.isEmpty()) null else s.copy(showValues = !s.showValues)
        MenuEntry.LYRICS -> if (!s.hasMusicAssistant) null else s.copy(lyrics = !s.lyrics)
        // "This TV" turns playing here on; leaving it goes back to the speaker followed before.
        MenuEntry.SPEAKER -> when {
            !s.hasMusicAssistant -> null
            s.entityId == EntityId.THIS_TV -> s.otherSpeaker?.let(s::following)
            else -> s.following(EntityId.THIS_TV).withPlayHere(true)
        }
        MenuEntry.OTHER_ROOMS -> if (s.otherRooms.isEmpty()) null else s.copy(showOtherRooms = !s.showOtherRooms)
        MenuEntry.PLAY_HERE -> {
            val next = move(PLAY_HERE_ORDER, s.playHere, direction, wrap)
            when {
                !s.hasMusicAssistant -> null
                // Turned off while this TV is followed, it goes back to the speaker followed before.
                next == PlayHere.OFF && followsThisTv(s) -> s.otherSpeaker?.let { s.following(it).copy(playHere = next) }
                else -> s.copy(playHere = next)
            }
        }
        MenuEntry.BACKGROUND -> s.copy(background = if (s.background == BackgroundMode.COVER) BackgroundMode.WALL else BackgroundMode.COVER)
        MenuEntry.MOTION -> s.copy(motion = move(MOTION_ORDER, s.motion, direction, wrap))
        MenuEntry.BRIGHTNESS -> if (s.roomLights.isSet) s.copy(roomLightBrightness = move(BRIGHTNESS_ORDER, s.roomLightBrightness, direction, wrap)) else null
        MenuEntry.OFF_LIGHTS -> if (s.roomLights.isSet) s.copy(lightsOnlyOn = !s.lightsOnlyOn) else null
        MenuEntry.APPEARANCE -> s.copy(appearance = move(APPEARANCE_ORDER, s.appearance, direction, wrap))
        MenuEntry.INTENSITY -> s.copy(intensity = move(INTENSITY_ORDER, s.intensity, direction, wrap))
        MenuEntry.REMOTE -> s.copy(remoteControl = !s.remoteControl)
        MenuEntry.UP_DOWN -> s.copy(volumeKeys = !s.volumeKeys)
        MenuEntry.SPOKEN -> s.copy(spokenSkip = !s.spokenSkip)
        MenuEntry.SLEEP_AFTER -> s.copy(sleepAfterMinutes = nextSleep(s.sleepAfterMinutes, direction, wrap))
        MenuEntry.SLEEP_ACTION -> s.copy(
            sleepAction = if (s.sleepAction == SleepAction.CLOSE) SleepAction.RELEASE else SleepAction.CLOSE,
        )
        MenuEntry.SCREEN_OFF -> if (s.presenceSensors.isEmpty()) null else s.copy(screenOff = !s.screenOff)
        MenuEntry.UPDATE_CHECK -> s.copy(updateCheck = !s.updateCheck)
        // OK on Update installs a new version or asks for one (MenuControl).
        MenuEntry.CONNECTION, MenuEntry.MUSIC_ASSISTANT, MenuEntry.SLEEP_TIMER, MenuEntry.LANGUAGE, MenuEntry.UPDATE -> null
    }

    /** The language after one step from [language] ([direction] -1 or +1; [wrap] goes round). */
    fun stepLanguage(language: AppLanguage, direction: Int, wrap: Boolean): AppLanguage = move(LANGUAGE_ORDER, language, direction, wrap)

    /** "Ten minutes", "One hour", "90 minutes", "Two hours": numbers up to twelve in words. */
    fun duration(minutes: Int, t: Texts): String =
        if (minutes >= 60 && minutes % 60 == 0) t.plural(Plurals.hours, minutes / 60) else t.plural(Plurals.minutes, minutes)

    /** "Off", "30 minutes", "1 hour", "End of chapter", "End of episode" and so on. */
    fun sleepValue(choice: SleepChoice, kind: MediaKind?, t: Texts): String = when (choice) {
        SleepChoice.OFF -> t[Strings.off]
        SleepChoice.CHAPTER_END -> t[Strings.sleepChapterEnd]
        SleepChoice.ITEM_END -> t[
            when (kind) {
                MediaKind.PODCAST -> Strings.sleepEpisodeEnd
                MediaKind.AUDIOBOOK -> Strings.sleepBookEnd
                else -> Strings.sleepTrackEnd
            },
        ]
        else -> duration(choice.minutes, t)
    }

    /** "end of the chapter", for the sentence that says when the music pauses. */
    private fun endPhrase(choice: SleepChoice, kind: MediaKind?): StringId = when {
        choice == SleepChoice.CHAPTER_END -> Strings.aboutSleepTimerEndChapter
        kind == MediaKind.PODCAST -> Strings.aboutSleepTimerEndEpisode
        kind == MediaKind.AUDIOBOOK -> Strings.aboutSleepTimerEndBook
        else -> Strings.aboutSleepTimerEndTrack
    }

    /** How the queue shows, in a word: Off, List, Cover Flow. */
    fun queueValue(queue: QueueDisplay, t: Texts): String = t[
        when (queue) {
            QueueDisplay.OFF -> Strings.off
            QueueDisplay.LIST -> Strings.queueList
            QueueDisplay.FLOW -> Strings.queueFlow
        },
    ]

    /** A language as the menu names it: English and German in their own words, the system's in the language shown. */
    fun languageValue(language: AppLanguage, t: Texts): String = t[
        when (language) {
            AppLanguage.SYSTEM -> Strings.languageSystem
            AppLanguage.ENGLISH -> Strings.languageEnglish
            AppLanguage.GERMAN -> Strings.languageGerman
        },
    ]

    /** From quiet to present: left means less, right means more. */
    val QUEUE_ORDER = listOf(QueueDisplay.OFF, QueueDisplay.LIST, QueueDisplay.FLOW)
    private val VOLUME_ORDER = listOf(VolumeDisplay.OFF, VolumeDisplay.ON_CHANGE, VolumeDisplay.ALWAYS)
    private val PLAY_HERE_ORDER = listOf(PlayHere.OFF, PlayHere.OPEN, PlayHere.ALWAYS)
    private val INTENSITY_ORDER = listOf(Intensity.OFF, Intensity.SUBTLE, Intensity.NORMAL, Intensity.STRONG)
    private val APPEARANCE_ORDER = listOf(AppearanceMode.DARK, AppearanceMode.AUTO, AppearanceMode.LIGHT)
    private val MOTION_ORDER = listOf(MotionLevel.OFF, MotionLevel.GENTLE, MotionLevel.MEDIUM, MotionLevel.LIVELY)
    private val BRIGHTNESS_ORDER = RoomLightBrightness.entries
    private val LANGUAGE_ORDER = listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.GERMAN)

    private fun <T> move(order: List<T>, current: T, direction: Int, wrap: Boolean): T {
        val i = order.indexOf(current).coerceAtLeast(0) + direction
        return if (wrap) order[i.mod(order.size)] else order[i.coerceIn(0, order.size - 1)]
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

    private fun onOff(on: Boolean, t: Texts) = t[if (on) Strings.on else Strings.off]

    /** Sentences one after the other. */
    private fun sentences(vararg s: String) = s.joinToString(" ")

    const val HOME_ASSISTANT = "Home Assistant"
    const val MUSIC_ASSISTANT = "Music Assistant"
}
