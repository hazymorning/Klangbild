package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.text.StringId
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts

/**
 * What takes the text column, the song's words or a few sentences about its artist, as its entry
 * in the quick settings says: on, off, or on while there are none for this song (NONE: the entry
 * still says On, in a quieter ink).
 */
enum class ColumnChoice { ON, OFF, NONE }

/** How far the current song's way into the favorites has come. */
enum class FavoriteState {
    /** Not asked yet (or not known to be one). */
    NONE,

    /** Asked, no answer yet. */
    BUSY,

    /** It is one: added just now, or Music Assistant says so. */
    DONE,

    /** Music Assistant could not find what plays (a station between songs, a song it does not know). */
    FAILED,
}

/**
 * One entry of the quick settings, in their order along the bottom edge: the built-in ones,
 * then the buttons set up on the phone ([ACTION], whose names are their own), then the way into
 * all settings ([SETTINGS]), and last, while music plays, the screen ([SCREEN]): it goes black
 * while the music and the lights go on, until a key is pressed.
 */
enum class QuickItem(val label: StringId?) {
    VIEW(Strings.quickView),
    QUEUE(Strings.quickQueue),
    LYRICS(Strings.quickLyrics),
    ARTIST(Strings.quickArtistInfo),
    PARTY(Strings.quickParty),
    FAVORITE(Strings.quickFavorite),
    LIGHTS(Strings.quickLights),
    SHUFFLE(Strings.quickShuffle),
    ACTION(null),
    SETTINGS(Strings.quickSettings),
    SCREEN(Strings.quickScreen),
}

/** Which entry: a built-in one, or the button at [action] (its place among the buttons set up). */
data class QuickKey(val item: QuickItem, val action: Int = -1)

/**
 * An entry as the quick settings show it: which it is, its name, its value in words, and every
 * value it can show (its place is as wide as the widest of them, so nothing moves when it changes).
 * A [muted] value holds but has nothing to show now (the song's words are on, and this song has
 * none), so it stands in a quieter ink.
 */
data class QuickRow(val key: QuickKey, val label: String, val value: String, val values: List<String> = listOf(value), val muted: Boolean = false) {
    val item: QuickItem get() = key.item
}

/** How a button of one's own stands. */
enum class ActionState {
    /** Ready: a script, a scene, an automation or a button. */
    READY,

    /** It ran just now. */
    DONE,

    /** Home Assistant could not run it. */
    FAILED,

    /** A switch that is on, or off. */
    ON,
    OFF,
}

/** What the quick settings need to know besides the settings. */
data class QuickContext(
    /** The view chosen with OK, applied once the presses stop. */
    val pending: StageView? = null,
    /** The next tracks can show as a list: Music Assistant, which tells the whole queue, is connected. */
    val list: Boolean = false,
    /** Something plays (or is paused) on the stage. */
    val music: Boolean = false,
    /**
     * Favorites can be asked for (a Music Assistant player, with a way to reach it), and how far
     * the current song's way has come.
     */
    val favorite: FavoriteState? = null,
    /** Shuffle on or off as far as known; null when the player cannot shuffle. */
    val shuffle: Boolean? = null,
    /**
     * The buttons that can be used now, by their place among the buttons, and how each stands.
     * A button whose entity Home Assistant does not know or has as unavailable, or whose
     * condition does not hold, is not here.
     */
    val actions: Map<Int, ActionState> = emptyMap(),
    /** The song's words as the entry says it; null without Music Assistant or without a song. */
    val lyrics: ColumnChoice? = null,
    /** Whether a party's code shows; null while no party of Music Assistant's plays to this speaker. */
    val party: Boolean? = null,
    /** What is known about the artist as the entry says it; null without Music Assistant or without a song. */
    val artist: ColumnChoice? = null,
)

/**
 * The quick settings: a row of a few entries that rises from the bottom edge when down is
 * pressed, for what is wanted now and then while watching and should not hide in the settings
 * (those stay on OK held). Left and right choose an entry, OK changes it (or does what it
 * says), up leaves the row. Entries that cannot do anything now are left out (no lyrics without
 * Music Assistant or without a song, no favorite without music, no lights before they are set
 * up, no shuffle on a player that cannot, no party code without a party playing here, no button
 * whose entity is unavailable or whose condition does not hold, no queue while Music Assistant
 * is not connected or Cover Flow shows the next tracks as covers, no screen without music). The
 * buttons set up on the phone come after the built-in entries, the way into the settings and
 * the screen last. The sleep timer lives in the settings.
 */
object QuickMenu {
    fun rows(s: Settings, ctx: QuickContext, t: Texts): List<QuickRow> = buildList {
        fun row(item: QuickItem, value: String, muted: Boolean = false) = add(QuickRow(QuickKey(item), t[item.label!!], value, values(item, t), muted))
        val view = ctx.pending ?: s.view
        row(QuickItem.VIEW, SettingsMenu.viewValue(view, t))
        if (ctx.list && view != StageView.FLOW) row(QuickItem.QUEUE, SettingsMenu.queueValue(s.queue, t))
        // While this song has no synced lyrics it still says On, only quieter, since a word like
        // "None" would look as if they had been switched off.
        if (ctx.lyrics != null) row(QuickItem.LYRICS, SettingsMenu.onOff(ctx.lyrics != ColumnChoice.OFF, t), muted = ctx.lyrics == ColumnChoice.NONE)
        if (ctx.artist != null) row(QuickItem.ARTIST, SettingsMenu.onOff(ctx.artist != ColumnChoice.OFF, t), muted = ctx.artist == ColumnChoice.NONE)
        if (ctx.music && ctx.party != null) row(QuickItem.PARTY, SettingsMenu.onOff(ctx.party, t))
        if (ctx.music && ctx.favorite != null) row(QuickItem.FAVORITE, favoriteValue(ctx.favorite, t))
        if (s.roomLights.isSet) row(QuickItem.LIGHTS, SettingsMenu.onOff(s.roomLightBrightness != RoomLightBrightness.OFF, t))
        if (ctx.music && ctx.shuffle != null) row(QuickItem.SHUFFLE, SettingsMenu.onOff(ctx.shuffle, t))
        s.quickActions.forEachIndexed { i, a ->
            val kind = a.kind ?: return@forEachIndexed
            val state = ctx.actions[i] ?: return@forEachIndexed
            add(QuickRow(QuickKey(QuickItem.ACTION, i), a.name, actionValue(kind, state, t), actionValues(kind, t)))
        }
        row(QuickItem.SETTINGS, t[Strings.quickOpen])
        if (ctx.music) row(QuickItem.SCREEN, t[Strings.quickTurnOff])
    }

    fun favoriteValue(f: FavoriteState, t: Texts): String = t[
        when (f) {
            FavoriteState.NONE -> Strings.favoriteAdd
            FavoriteState.BUSY -> Strings.favoriteAdding
            FavoriteState.DONE -> Strings.favoriteAdded
            FavoriteState.FAILED -> Strings.actionFailed
        },
    ]

    /** A button's value: what it does, how it went, or (a switch) whether it is on. */
    fun actionValue(kind: ActionKind, state: ActionState, t: Texts): String = t[
        when (state) {
            ActionState.DONE -> Strings.actionDone
            ActionState.FAILED -> Strings.actionFailed
            ActionState.ON -> Strings.on
            ActionState.OFF -> Strings.off
            ActionState.READY -> when (kind) {
                ActionKind.RUN -> Strings.actionRun
                ActionKind.ACTIVATE -> Strings.actionActivate
                ActionKind.PRESS -> Strings.actionPress
                ActionKind.TOGGLE -> Strings.off
            }
        },
    ]

    /** Every value a button of [kind] can show. */
    fun actionValues(kind: ActionKind, t: Texts): List<String> =
        (if (kind == ActionKind.TOGGLE) listOf(ActionState.ON, ActionState.OFF, ActionState.FAILED) else listOf(ActionState.READY, ActionState.DONE, ActionState.FAILED))
            .map { actionValue(kind, it, t) }

    /**
     * Every value [item] can show, so that its place is as wide as the widest of them and
     * nothing moves when a value changes (for the buttons: what any of them can show).
     */
    fun values(item: QuickItem, t: Texts): List<String> = when (item) {
        QuickItem.VIEW -> SettingsMenu.VIEW_ORDER.map { SettingsMenu.viewValue(it, t) }
        QuickItem.QUEUE -> SettingsMenu.QUEUE_ORDER.map { SettingsMenu.queueValue(it, t) }
        QuickItem.FAVORITE -> FavoriteState.entries.map { favoriteValue(it, t) }
        QuickItem.LYRICS, QuickItem.ARTIST, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.PARTY -> listOf(t[Strings.on], t[Strings.off])
        QuickItem.ACTION -> ActionKind.entries.flatMap { actionValues(it, t) }.distinct()
        QuickItem.SETTINGS -> listOf(t[Strings.quickOpen])
        QuickItem.SCREEN -> listOf(t[Strings.quickTurnOff])
    }
}
