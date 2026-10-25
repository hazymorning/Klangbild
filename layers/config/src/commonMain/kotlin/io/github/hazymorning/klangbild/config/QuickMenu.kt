package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.text.StringId
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts

/**
 * The song's words in the text column: on, off, or on while this song has no synced words
 * (NONE: the entry still says On, in a quieter ink).
 */
enum class LyricsChoice { ON, OFF, NONE }

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
 * all settings ([SETTINGS]).
 */
enum class QuickItem(val label: StringId?) {
    QUEUE(Strings.quickQueue),
    LYRICS(Strings.quickLyrics),
    PARTY(Strings.quickParty),
    FAVORITE(Strings.quickFavorite),
    LIGHTS(Strings.quickLights),
    SHUFFLE(Strings.quickShuffle),
    ACTION(null),
    SETTINGS(Strings.quickSettings),
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
    /** How the queue should show, chosen with OK and applied once the presses stop. */
    val pending: QueueDisplay? = null,
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
    val lyrics: LyricsChoice? = null,
    /** Whether a party's code shows; null while no party of Music Assistant's plays to this speaker. */
    val party: Boolean? = null,
)

/**
 * The quick settings: a row of a few entries that rises from the bottom edge when down is
 * pressed, for what is wanted now and then while watching and should not hide in the settings
 * (those stay on OK held). Left and right choose an entry, OK changes it (or does what it
 * says), up leaves the row. Entries that cannot do anything now are left out (no lyrics without
 * Music Assistant or without a song, no favorite without music, no lights before they are set
 * up, no shuffle on a player that cannot, no party code without a party playing here, no button
 * whose entity is unavailable or whose condition does not hold), and the queue's entry passes
 * over its list while Music Assistant is not connected. The buttons set up on the phone come
 * last. The sleep timer lives in the settings.
 */
object QuickMenu {
    fun rows(s: Settings, ctx: QuickContext, t: Texts): List<QuickRow> = buildList {
        fun row(item: QuickItem, value: String, muted: Boolean = false) = add(QuickRow(QuickKey(item), t[item.label!!], value, values(item, t), muted))
        row(QuickItem.QUEUE, SettingsMenu.queueValue(ctx.pending ?: s.queue, t))
        // While this song has no synced lyrics it still says On, only quieter, since a word like
        // "None" would look as if they had been switched off.
        if (ctx.lyrics != null) row(QuickItem.LYRICS, lyricsValue(ctx.lyrics, t), muted = ctx.lyrics == LyricsChoice.NONE)
        if (ctx.music && ctx.party != null) row(QuickItem.PARTY, t[if (ctx.party) Strings.on else Strings.off])
        if (ctx.music && ctx.favorite != null) row(QuickItem.FAVORITE, favoriteValue(ctx.favorite, t))
        if (s.roomLights.isSet) row(QuickItem.LIGHTS, t[if (s.roomLightBrightness == RoomLightBrightness.OFF) Strings.off else Strings.on])
        if (ctx.music && ctx.shuffle != null) row(QuickItem.SHUFFLE, t[if (ctx.shuffle) Strings.on else Strings.off])
        s.quickActions.forEachIndexed { i, a ->
            val kind = a.kind ?: return@forEachIndexed
            val state = ctx.actions[i] ?: return@forEachIndexed
            add(QuickRow(QuickKey(QuickItem.ACTION, i), a.name, actionValue(kind, state, t), actionValues(kind, t)))
        }
        row(QuickItem.SETTINGS, t[Strings.quickOpen])
    }

    fun lyricsValue(l: LyricsChoice, t: Texts): String = t[if (l == LyricsChoice.OFF) Strings.off else Strings.on]

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
        QuickItem.QUEUE -> SettingsMenu.QUEUE_ORDER.map { SettingsMenu.queueValue(it, t) }
        QuickItem.FAVORITE -> FavoriteState.entries.map { favoriteValue(it, t) }
        QuickItem.LYRICS, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.PARTY -> listOf(t[Strings.on], t[Strings.off])
        QuickItem.ACTION -> ActionKind.entries.flatMap { actionValues(it, t) }.distinct()
        QuickItem.SETTINGS -> listOf(t[Strings.quickOpen])
    }

    /**
     * How the queue shows after one step from [q] (−1 or +1), from the quietest to the fullest;
     * [wrap] goes round. The list is passed over while it cannot show ([list] false).
     */
    fun step(q: QueueDisplay, direction: Int, wrap: Boolean, list: Boolean): QueueDisplay {
        val order = SettingsMenu.QUEUE_ORDER
        var i = order.indexOf(q)
        repeat(order.size) {
            i += direction
            i = if (wrap) i.mod(order.size) else i.coerceIn(0, order.size - 1)
            if (order[i] != QueueDisplay.LIST || list) return order[i]
        }
        return q
    }
}
