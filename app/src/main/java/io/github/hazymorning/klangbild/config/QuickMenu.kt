package io.github.hazymorning.klangbild.config

import androidx.annotation.StringRes
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.text.Texts

/** The song's words in the text column: on, off, or on while this song has no synced words. */
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
enum class QuickItem(@StringRes val label: Int?) {
    QUEUE(R.string.quick_queue),
    LANDSCAPE(R.string.quick_landscape),
    LYRICS(R.string.quick_lyrics),
    PARTY(R.string.quick_party),
    FAVORITE(R.string.quick_favorite),
    LIGHTS(R.string.quick_lights),
    SHUFFLE(R.string.quick_shuffle),
    ACTION(null),
    SETTINGS(R.string.quick_settings),
}

/** Which entry: a built-in one, or the button at [action] (its place among the buttons set up). */
data class QuickKey(val item: QuickItem, val action: Int = -1)

/**
 * An entry as the quick settings show it: which it is, its name, its value in words, and every
 * value it can show (its place is as wide as the widest of them, so nothing moves when it changes).
 */
data class QuickRow(val key: QuickKey, val label: String, val value: String, val values: List<String> = listOf(value)) {
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
    /** The screen shows the landscape. */
    val landscape: Boolean = false,
    /** How the queue is to show, chosen with OK and taken once the presses stop. */
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
        fun row(item: QuickItem, value: String) = add(QuickRow(QuickKey(item), t[item.label!!], value, values(item, t)))
        row(QuickItem.QUEUE, SettingsMenu.queueValue(ctx.pending ?: s.queue, t))
        row(QuickItem.LANDSCAPE, t[if (ctx.landscape) R.string.on else R.string.off])
        if (ctx.lyrics != null) row(QuickItem.LYRICS, lyricsValue(ctx.lyrics, t))
        if (ctx.music && ctx.party != null) row(QuickItem.PARTY, t[if (ctx.party) R.string.on else R.string.off])
        if (ctx.music && ctx.favorite != null) row(QuickItem.FAVORITE, favoriteValue(ctx.favorite, t))
        if (s.roomLights.isSet) row(QuickItem.LIGHTS, t[if (s.roomLightBrightness == RoomLightBrightness.OFF) R.string.off else R.string.on])
        if (ctx.music && ctx.shuffle != null) row(QuickItem.SHUFFLE, t[if (ctx.shuffle) R.string.on else R.string.off])
        s.quickActions.forEachIndexed { i, a ->
            val kind = a.kind ?: return@forEachIndexed
            val state = ctx.actions[i] ?: return@forEachIndexed
            add(QuickRow(QuickKey(QuickItem.ACTION, i), a.name, actionValue(kind, state, t), actionValues(kind, t)))
        }
        row(QuickItem.SETTINGS, t[R.string.quick_open])
    }

    fun lyricsValue(l: LyricsChoice, t: Texts): String = t[
        when (l) {
            LyricsChoice.ON -> R.string.on
            LyricsChoice.OFF -> R.string.off
            LyricsChoice.NONE -> R.string.lyrics_none
        },
    ]

    fun favoriteValue(f: FavoriteState, t: Texts): String = t[
        when (f) {
            FavoriteState.NONE -> R.string.favorite_add
            FavoriteState.BUSY -> R.string.favorite_adding
            FavoriteState.DONE -> R.string.favorite_added
            FavoriteState.FAILED -> R.string.favorite_failed
        },
    ]

    /** A button's value: what it does, how it went, or (a switch) whether it is on. */
    fun actionValue(kind: ActionKind, state: ActionState, t: Texts): String = t[
        when (state) {
            ActionState.DONE -> R.string.action_done
            ActionState.FAILED -> R.string.action_failed
            ActionState.ON -> R.string.on
            ActionState.OFF -> R.string.off
            ActionState.READY -> when (kind) {
                ActionKind.RUN -> R.string.action_run
                ActionKind.ACTIVATE -> R.string.action_activate
                ActionKind.PRESS -> R.string.action_press
                ActionKind.TOGGLE -> R.string.off
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
        QuickItem.LYRICS -> LyricsChoice.entries.map { lyricsValue(it, t) }
        QuickItem.FAVORITE -> FavoriteState.entries.map { favoriteValue(it, t) }
        QuickItem.LANDSCAPE, QuickItem.LIGHTS, QuickItem.SHUFFLE, QuickItem.PARTY -> listOf(t[R.string.on], t[R.string.off])
        QuickItem.ACTION -> ActionKind.entries.flatMap { actionValues(it, t) }.distinct()
        QuickItem.SETTINGS -> listOf(t[R.string.quick_open])
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
            i = if (wrap) Math.floorMod(i, order.size) else i.coerceIn(0, order.size - 1)
            if (order[i] != QueueDisplay.LIST || list) return order[i]
        }
        return q
    }
}
