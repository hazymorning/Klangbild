package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.config.ActionState
import io.github.hazymorning.klangbild.config.FavoriteState
import io.github.hazymorning.klangbild.config.LyricsChoice
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickKey
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.QuickRow
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.RoomLightBrightness
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The quick settings while they are open (down on the remote): which entry the cursor is on,
 * and what OK does to it. While they are open, the landscape stays as it was unless they
 * change it. Untouched, they close after [IDLE_MS].
 */
internal class QuickControl(private val host: ControllerHost) {
    /** The entry the cursor is on, by what it is, so entries coming and going never move it. */
    @Volatile private var cursor: QuickKey? = null
    @Volatile private var touchedAt = 0L

    /** Whether the landscape showed when the quick settings opened: it stays as it was while they are open. */
    @Volatile private var hold: Boolean? = null

    /** How the queue is to show, chosen with OK, taken once the presses stop (so a step through them never shows the one between). */
    @Volatile private var pendingQueue: QueueDisplay? = null
    private var pictureJob: Job? = null

    /** The lights' level before they were switched off from the quick settings. */
    private var lightsBefore = RoomLightBrightness.DEFAULT

    /** The current song's way into the favorites (by the song's key). */
    @Volatile private var favorite: Pair<String, FavoriteState>? = null

    /** Shuffle just asked for, and until when it is shown before the player has reported it. */
    @Volatile private var shuffleWanted: Pair<Boolean, Long>? = null

    /** The buttons whose call is out, by their place among the buttons: OK waits for the answer. */
    private val running = HashSet<Int>()

    /** How each button's last call went, by its place, and until when it says so. */
    private val outcome = HashMap<Int, Pair<ActionState, Long>>()

    /** A switch just turned from the quick settings: shown as asked until Home Assistant reports it, or a while. */
    private val switchWanted = HashMap<String, Pair<Boolean, Long>>()

    /** The party's code is to show (OK on "Party code"); a party that ends switches it off. */
    @Volatile private var partyOn = false

    /** The link guests open, while the code is to show: asked when it is switched on, and again now and then (a code expires). */
    @Volatile private var partyUrl: String? = null
    private var partyJob: Job? = null

    val isOpen: Boolean get() = cursor != null

    /** Opens the quick settings on the queue's entry. Once opened, the stage no longer tells how. */
    fun open(menuOpen: Boolean) {
        synchronized(host.lock) {
            if (menuOpen || cursor != null) return
            cursor = QuickKey(QuickItem.QUEUE)
            touchedAt = host.now()
            hold = host.ui.landscape != null
            host.recompute()
        }
        host.settings.let { s -> if (!s.quickSeen) host.applySettings(s.copy(quickSeen = true)) }
    }

    /** Closes the quick settings (up, back, after a while alone); a choice still waiting is taken now. */
    fun close() {
        applyQueue()
        synchronized(host.lock) {
            cursor = null
            hold = null
        }
        host.recompute()
    }

    /** A key in the quick settings that changes nothing: they stay open a while longer. */
    fun touch() {
        touchedAt = host.now()
    }

    /** Left (-1) or right (+1) along the quick settings; it stops at their ends. */
    fun move(delta: Int) {
        synchronized(host.lock) {
            val c = cursor ?: return
            val keys = rows().map { it.key }
            cursor = keys[(indexOf(keys, c) + delta).coerceIn(0, keys.size - 1)]
            touchedAt = host.now()
            host.recompute()
        }
    }

    /** OK on the entry under the cursor: its next value, or what it says. */
    fun change() {
        val row = synchronized(host.lock) {
            val c = cursor ?: return
            touchedAt = host.now()
            val rows = rows()
            rows.getOrNull(indexOf(rows.map { it.key }, c))
        } ?: return
        when (row.item) {
            QuickItem.QUEUE -> synchronized(host.lock) {
                val shown = pendingQueue ?: host.settings.queue
                pendingQueue = QuickMenu.step(shown, +1, wrap = true, list = listCanShow())
                pictureJob?.cancel()
                pictureJob = host.scope.launch {
                    delay(SETTLE_MS)
                    applyQueue()
                }
                host.recompute()
            }
            QuickItem.LANDSCAPE -> toggleLandscape()
            // On and off, at once: the stage changes its text as it does for a new song.
            QuickItem.LYRICS -> host.settings.let { s -> host.applySettings(s.copy(lyrics = !s.lyrics)) }
            QuickItem.FAVORITE -> addFavorite()
            QuickItem.LIGHTS -> {
                // On and off: off remembers the level, on brings it back.
                val s = host.settings
                val level = if (s.roomLightBrightness == RoomLightBrightness.OFF) lightsBefore else RoomLightBrightness.OFF
                if (s.roomLightBrightness != RoomLightBrightness.OFF) lightsBefore = s.roomLightBrightness
                host.applySettings(s.copy(roomLightBrightness = level))
            }
            QuickItem.SHUFFLE -> toggleShuffle()
            QuickItem.PARTY -> toggleParty()
            QuickItem.ACTION -> host.settings.quickActions.getOrNull(row.key.action)?.let { runAction(row.key.action, it) }
            // The way into all settings: the row makes way for the menu.
            QuickItem.SETTINGS -> {
                close()
                host.openMenu()
            }
        }
    }

    /**
     * Closes quick settings nobody has touched for [IDLE_MS], or that the settings menu replaced
     * (called under the lock).
     */
    fun expire(t: Long, menuOpen: Boolean) {
        if (cursor != null && (t - touchedAt > IDLE_MS || menuOpen)) {
            cursor = null
            hold = null
            if (pendingQueue != null) host.scope.launch { applyQueue() }
        }
    }

    /** Whether the landscape shows while the quick settings are open; null when they leave it to its delays. */
    val landscapeHeld: Boolean? get() = hold?.takeIf { cursor != null }

    /**
     * Whether the landscape is chosen to show, as the last [state] had it: its entry names and
     * turns that (a landscape kept a moment longer while a song's first scene is made does not
     * count).
     */
    private var landscapeChosen = false

    /** The quick settings as they show for [screen], null while they are closed (called under the lock). */
    fun state(screen: Screen, landscape: Boolean): QuickState? {
        landscapeChosen = landscape
        val c = cursor ?: return null
        val rows = rows(context(screen, landscape))
        return QuickState(rows, indexOf(rows.map { it.key }, c))
    }

    /**
     * Takes how the queue is to show, as chosen in the quick settings. Another choice over the
     * landscape leaves it ([leave]): the stage comes to show it. Stepped round to the one it had,
     * nothing changes.
     */
    private fun applyQueue(leave: Boolean = true) {
        val next = synchronized(host.lock) {
            pictureJob?.cancel()
            pictureJob = null
            pendingQueue.also { pendingQueue = null }
        } ?: return
        val s = host.settings
        if (next == s.queue) return
        // The setting first: the landscape stays until the stage's first scene is made, and
        // the composition before never shows in between.
        host.applySettings(s.copy(queue = next))
        if (!leave) return
        if (hold != null) hold = false
        host.hideLandscape()
    }

    /**
     * The landscape now, or no longer, at once. A queue still waiting to be taken is taken
     * first, without leaving the landscape.
     */
    private fun toggleLandscape() {
        applyQueue(leave = false)
        val on = synchronized(host.lock) { !landscapeChosen }
        if (hold != null) hold = on
        if (on) host.showLandscape() else host.hideLandscape()
    }

    /** The queue can show as a list: Music Assistant, which tells the whole queue, is connected. */
    private fun listCanShow(): Boolean = host.ui.source.ma == MaLink.CONNECTED

    /** Adds what plays to Music Assistant's favorites; the quick settings say how it went. */
    private fun addFavorite() {
        val track = (host.ui.screen as? Screen.Stage)?.track ?: return
        val src = host.source ?: return
        synchronized(host.lock) {
            val state = favoriteState(track)
            if (state == FavoriteState.BUSY || state == FavoriteState.DONE) return
            favorite = track.key to FavoriteState.BUSY
        }
        host.recompute()
        host.scope.launch {
            val ok = src.favorite()
            synchronized(host.lock) {
                if (favorite?.first == track.key) favorite = track.key to if (ok) FavoriteState.DONE else FavoriteState.FAILED
            }
            host.recompute()
        }
    }

    /**
     * Does what the button at [index] says, once at a time. A switch shows its new state at once
     * (Home Assistant reports it a moment later); the others say how it went for [RESULT_MS].
     */
    private fun runAction(index: Int, action: QuickAction) {
        val src = host.source ?: return
        val kind = action.kind ?: return
        val entity = action.entity
        synchronized(host.lock) {
            if (index in running) return
            if (kind == ActionKind.TOGGLE) {
                val on = switchShown(entity) ?: return
                switchWanted[entity] = !on to host.now() + SWITCH_TRUST_MS
            }
            running += index
            outcome.remove(index)
        }
        host.recompute()
        host.scope.launch {
            val ok = src.run(action)
            synchronized(host.lock) {
                running -= index
                if (!ok) switchWanted.remove(entity)
                // A switch tells by its state; only a failure is said in words.
                if (kind != ActionKind.TOGGLE || !ok) outcome[index] = (if (ok) ActionState.DONE else ActionState.FAILED) to host.now() + RESULT_MS
            }
            host.recompute()
            // Back to what the button does once the word has been read.
            delay(RESULT_MS + 50)
            host.recompute()
        }
    }

    /**
     * Shows the party's code, or no longer. On, its link is asked for at once and again every
     * [PARTY_URL_MS] (soon again when no answer came); a link once had stays until a new one
     * comes. The code shows at once: the landscape makes way.
     */
    private fun toggleParty() {
        val src = host.source ?: return
        val on = synchronized(host.lock) {
            partyOn = !partyOn
            partyJob?.cancel()
            partyJob = null
            partyUrl = null
            if (partyOn) {
                partyJob = host.scope.launch {
                    while (true) {
                        val url = src.partyUrl()
                        synchronized(host.lock) { if (partyOn && url != null) partyUrl = url }
                        host.recompute()
                        delay(if (url != null) PARTY_URL_MS else PARTY_RETRY_MS)
                    }
                }
            }
            partyOn
        }
        if (on) {
            if (hold != null) hold = false
            host.hideLandscape()
        } else {
            host.recompute()
        }
    }

    /**
     * Whether the party's code is to show, with a party playing [here]: a party that has ended
     * (or plays elsewhere) switches it off, so the next one starts without it (called under the lock).
     */
    fun partyWanted(here: Boolean): Boolean {
        if (!here && partyOn) {
            partyOn = false
            partyUrl = null
            partyJob?.cancel()
            partyJob = null
        }
        return partyOn
    }

    /** The link to show as a code now, once it has come (called under the lock, after [partyWanted]). */
    val partyCode: String? get() = partyUrl.takeIf { partyOn }

    /**
     * Turns shuffle the other way: shown at once and sent to the player, which reports it back in
     * a moment. Until then (a few seconds at most) the quick settings show what was asked for.
     */
    private fun toggleShuffle() {
        val src = host.source ?: return
        val on = synchronized(host.lock) {
            val now = shuffleShown() ?: return
            shuffleWanted = !now to host.now() + SHUFFLE_TRUST_MS
            !now
        }
        host.recompute()
        host.scope.launch {
            if (!src.shuffle(on)) synchronized(host.lock) { shuffleWanted = null }
            host.recompute()
        }
    }

    /** Shuffle as the quick settings show it: what was just asked for, else what the player says. */
    private fun shuffleShown(): Boolean? {
        val reported = host.ui.source.player?.shuffle ?: return null
        val wanted = shuffleWanted
        if (wanted == null || host.now() > wanted.second || wanted.first == reported) {
            shuffleWanted = null
            return reported
        }
        return wanted.first
    }

    /** Whether the switch [entity] shows as on: what was just asked for, else what Home Assistant says; null without a state. */
    private fun switchShown(entity: String): Boolean? {
        val reported = when (host.ui.source.entities[entity]?.state) {
            "on" -> true
            "off" -> false
            else -> null
        }
        val wanted = switchWanted[entity]
        if (wanted == null || host.now() > wanted.second || wanted.first == reported) {
            switchWanted.remove(entity)
            return reported
        }
        return wanted.first
    }

    /**
     * How each button stands now, by its place among the buttons. A button whose entity Home
     * Assistant does not know (yet), or has as unavailable, is left out; so is a switch that is
     * neither on nor off, and a button whose condition does not hold (unless it is just saying
     * how its call went: what it did may have ended the condition).
     */
    private fun actionStates(): Map<Int, ActionState> {
        val entities = host.ui.source.entities
        val t = host.now()
        val out = HashMap<Int, ActionState>()
        for ((i, a) in host.settings.quickActions.withIndex()) {
            val kind = a.kind ?: continue
            val state = entities[a.entity]?.state ?: continue
            if (state == UNAVAILABLE) continue
            val said = outcome[i]?.takeIf { t < it.second }?.first
            val c = a.condition
            if (c != null && i !in running && said == null && !entities[c.entity].let { e -> c.holds(e?.state, e?.attributes) }) continue
            out[i] = when {
                said != null -> said
                kind == ActionKind.TOGGLE -> when (switchShown(a.entity)) {
                    true -> ActionState.ON
                    false -> ActionState.OFF
                    null -> continue
                }
                else -> ActionState.READY
            }
        }
        return out
    }

    /**
     * The lyrics entry for [track]: only for a song, while Music Assistant (where the words come
     * from) is connected. None while it is on and Music Assistant has no synced words for the song.
     */
    private fun lyricsChoice(track: TrackInfo): LyricsChoice? {
        if (track.kind != MediaKind.MUSIC || host.ui.source.ma != MaLink.CONNECTED) return null
        if (!host.settings.lyrics) return LyricsChoice.OFF
        val known = host.ui.source.lyrics?.takeIf { it.key == track.key }
        return if ((known != null && known.lyrics == null) || track.durationMs == null) LyricsChoice.NONE else LyricsChoice.ON
    }

    /** How far [track]'s way into the favorites has come: asked here, or known from Music Assistant. */
    private fun favoriteState(track: TrackInfo): FavoriteState =
        favorite?.takeIf { it.first == track.key }?.second ?: if (track.favorite) FavoriteState.DONE else FavoriteState.NONE

    /**
     * What the quick settings need to know: by default what the screen shows now, while working out
     * the screen what it is about to show.
     */
    private fun context(screen: Screen = host.ui.screen, landscape: Boolean = landscapeChosen): QuickContext {
        val track = (screen as? Screen.Stage)?.track
        return QuickContext(
            landscape = landscape,
            pending = pendingQueue,
            music = track != null,
            favorite = track?.takeIf { host.ui.source.favorites }?.let { favoriteState(it) },
            shuffle = shuffleShown(),
            actions = actionStates(),
            lyrics = track?.let { lyricsChoice(it) },
            party = partyOn.takeIf { host.ui.source.party },
        )
    }

    private fun rows(ctx: QuickContext = context()): List<QuickRow> = QuickMenu.rows(host.settings, ctx, host.texts)

    /**
     * Where [key] stands among [keys]; once it is gone (the favorite when the music stops), the
     * entry that took its place.
     */
    private fun indexOf(keys: List<QuickKey>, key: QuickKey): Int {
        keys.indexOf(key).takeIf { it >= 0 }?.let { return it }
        val after = keys.indexOfFirst { it.item.ordinal > key.item.ordinal || (it.item == key.item && it.action > key.action) }
        return after.takeIf { it >= 0 } ?: (keys.size - 1)
    }

    companion object {
        /** Untouched quick settings close after this long: they are for a moment's choice. */
        const val IDLE_MS = 20_000L

        /** How the queue is to show, chosen in the quick settings, is taken this long after the last press. */
        const val SETTLE_MS = 900L

        /** Shuffle just asked for is shown this long before the player has reported it. */
        const val SHUFFLE_TRUST_MS = 6_000L

        /** A switch just turned is shown as asked this long before Home Assistant has reported it. */
        const val SWITCH_TRUST_MS = 6_000L

        /** How long a button says how its call went before it says again what it does. */
        const val RESULT_MS = 4_000L

        /** While the party's code shows, its link is asked for again this often: Music Assistant renews an expired code. */
        const val PARTY_URL_MS = 60_000L

        /** When the party's link could not be had, it is asked for again after this long. */
        const val PARTY_RETRY_MS = 5_000L

        /** Home Assistant's state for an entity that is there but cannot be used. */
        private const val UNAVAILABLE = "unavailable"
    }
}
