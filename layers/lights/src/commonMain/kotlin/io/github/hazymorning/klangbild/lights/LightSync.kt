package io.github.hazymorning.klangbild.lights

import io.github.hazymorning.klangbild.config.RoomLightBrightness
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.data.LightBridge
import io.github.hazymorning.klangbild.domain.EntityState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Any lights Home Assistant controls, in the colors of what the screen shows: each light a color
 * of its own ([LightColors]). These are the cover's most telling colors, kept apart and placed
 * as they sit in the cover (with motion on they move on from light to light), or in the
 * landscape its sky, the sun's glow on its side and the campfire's light on the fire's. A light
 * shows what it can: a color, a warmer or cooler white, a brightness, or just on.
 *
 * It is gentle on the lights' network and on whoever else uses the lights:
 *
 *  - before it takes a light over it saves the light's state with Home Assistant's own
 *    snapshot (`scene.create`), and restores it when the music has ended for a while, when the
 *    option is switched off and when the app closes;
 *  - one call per light at most every [MIN_GAP_MS], the newest wins; nothing is sent for a
 *    change too small to see; a failed call is tried once more, for that light only;
 *  - the lights' states arrive a moment after they are first followed: it waits for them (a
 *    while at most), and takes over a light that only shows up later, saving its state first;
 *    a light taken over stays followed until it is given back, so it goes back gently;
 *  - a new song is shown once it has played for [SETTLE_MS] (quick skips cost nothing), in a
 *    [TRACK_TRANSITION_MS] fade; drifting colors move in long fades every [DRIFT_MS];
 *  - by day the lights glow at the chosen level, at night at [NIGHT] of it (by the sun); a pause
 *    dims them to half;
 *  - a light someone else changes (switched off, set to white, much brighter or darker, another
 *    color from Home Assistant) is left alone until the next time music starts;
 *  - with [Target.onlyOn] ("Lights that are off: Leave off") a light that is off is never
 *    switched on: one switched on during the music joins in, one switched off is let go until
 *    it is switched on again, and each goes back to how it was before it first joined.
 *
 * All its state lives on one thread; [update] and [release] only hand over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSync(
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val context: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) {
    /** What the lights are to show now, set on every tick; null while nothing is on stage. */
    data class Target(
        val lights: RoomLights,
        val level: RoomLightBrightness,
        /** The colors for each light; a new one is faded to. */
        val colors: LightColors,
        /** The song (or the landscape): a new one changes the lights once it has played a moment. */
        val song: String,
        val playing: Boolean,
        /** 0 by day to 1 at night (by the sun): the lights dim with the evening. */
        val night: Float,
        /** Grays become a warmer white toward the night (0 to 1). */
        val warmth: Float,
        /** How fast the field moves (0: it stands still). */
        val motion: Float,
        /** The field's time (seconds), shared with the screen. */
        val fieldTime: () -> Double,
        /**
         * The landscape shows: its light changes slowly, so the lights follow it in long fades
         * and seldom calls; a pause does not dim them (the picture is the show).
         */
        val landscape: Boolean = false,
        /** Lights that are off are left alone; one switched on joins in, one switched off is let go. */
        val onlyOn: Boolean = false,
    )

    /** What a light can show, from its attributes (https://developers.home-assistant.io/docs/core/entity/light). */
    private class Caps(
        /** It takes a color (hs, xy, rgb, rgbw or rgbww). */
        val color: Boolean,
        /** It takes a color temperature. */
        val temperature: Boolean,
        /** It takes a brightness. */
        val dimmable: Boolean,
        /** It fades (`LightEntityFeature.TRANSITION`). */
        val transition: Boolean,
        val minKelvin: Int,
        val maxKelvin: Int,
    )

    /** What a light was last asked to show. */
    private sealed interface Shown {
        data class Color(val xy: Xy, val brightness: Int) : Shown
        data class White(val kelvin: Int, val brightness: Int) : Shown
        data class Level(val brightness: Int) : Shown
        data object On : Shown
    }

    /** A light's state when it was taken over: on or off, and enough to restore it if the snapshot is gone. */
    private class Saved(val on: Boolean, val brightness: Int?, val mode: String?, val xy: List<Float>?, val hs: List<Float>?, val kelvin: Int?)

    private class Light(val id: String, val slot: LightSlot) {
        var shown: Shown? = null
        var sentAt = 0L
        var transitionMs = 0L
        var inFlight = false
        var retryAt = 0L
        var takenOver = false

        /** Calls that failed in a row: one is tried again, then the light waits for the next change. */
        var failures = 0

        /** The last state change already judged (Home Assistant's time stamp). */
        var checkedUpdate = 0.0
        val contexts = ArrayDeque<String>()
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var target: Target? = null

    /** The last target there was: when the music has ended the lights keep its colors, dimmed. */
    @Volatile private var lastTarget: Target? = null
    @Volatile private var bridge: LightBridge? = null
    private var loop: Job? = null
    private val lock = SynchronizedObject()

    /** The lights being given back: a new loop waits for it. */
    @Volatile private var releasing: Job? = null

    /** When the lights were last asked to be followed: their states arrive a moment later. */
    @Volatile private var followedAt = 0L

    /**
     * The lights asked for when they were taken over (some may have been unavailable), until
     * they are given back; written on the loop's thread. They stay followed until then.
     */
    @Volatile private var activeFor: Set<String>? = null

    // Confined to [context]:
    private val lights = LinkedHashMap<String, Light>()

    /** The lights were taken over with [Target.onlyOn]. */
    private var takenOnlyOn = false

    /**
     * Lights turned back off for "Leave off", and when: one Home Assistant still reports on is
     * not taken up again for [GIVEN_BACK_MS].
     */
    private val givenBack = HashMap<String, Long>()

    private var saved: Map<String, Saved> = emptyMap()

    /** The lights whose state Home Assistant holds in a snapshot scene of ours. */
    private var snapshots: Set<String> = emptySet()
    private var savedAt = 0L
    private var shownSong: String? = null
    private var shownColors: LightColors? = null
    private var waitingSong: String? = null
    private var waitingSince = 0L
    private var idleSince: Long? = null
    private var pausedSince: Long? = null
    private var shownPaused = false
    private var lastStepAt = 0L
    private var shownShare = 0f
    private val rgb = FloatArray(3)

    /** The Home Assistant link the calls go through (a new one after a new connection). */
    fun attach(bridge: LightBridge?) {
        this.bridge = bridge
        follow()
        if (bridge != null) ensureLoop()
        wake.trySend(Unit)
    }

    /** Asks the link for the states of the lights wanted now; whichever call comes last decides. */
    private fun follow(): Unit = synchronized(lock) {
        val ids = followed(target)
        if (ids.isNotEmpty()) followedAt = now()
        bridge?.follow(ids)
    }

    /**
     * What the lights are to show now ([target], null while nothing shows), and which lights are
     * [chosen] and switched on (null: as the target says): lights no longer chosen, or switched
     * off, are given back at once, also while nothing shows.
     */
    fun update(target: Target?, chosen: Set<String>? = null) {
        val old = this.target
        this.target = target
        this.chosen = chosen
        if (target != null) lastTarget = target
        if (old?.lights != target?.lights || (old?.level == RoomLightBrightness.OFF) != (target?.level == RoomLightBrightness.OFF)) follow()
        // Nothing to do without lights, unless lights taken over are still to be given back.
        if (target != null || activeLights) ensureLoop()
        if (old?.song != target?.song || old?.playing != target?.playing || old?.level != target?.level || old?.onlyOn != target?.onlyOn || old == null || target == null) wake.trySend(Unit)
    }

    /** Whether lights are taken over (read off the loop's thread; a stale answer only delays the loop by a tick). */
    @Volatile private var activeLights = false

    /** The lights chosen and switched on, as the last [update] said; null: as the target says. */
    @Volatile private var chosen: Set<String>? = null

    /**
     * Gives the lights back as they were, through the link they were taken over with (the app
     * closes, or another server is set up); the returned job ends when the calls are out.
     */
    fun release(): Job {
        val b = bridge
        target = null
        val old = synchronized(lock) { loop.also { loop = null } }
        old?.cancel()
        val job = scope.launch(context) {
            old?.join()
            restore(b)
        }
        releasing = job
        return job
    }

    private fun ensureLoop(): Unit = synchronized(lock) {
        if (loop?.isActive == true || bridge == null) return
        // Lights still being given back are given back first.
        val giving = releasing
        loop = scope.launch(context) {
            giving?.join()
            while (isActive) {
                step(now())
                // Nothing taken over and nothing to show: the loop rests until it is needed again.
                if (activeFor == null && target == null) {
                    synchronized(lock) { loop = null }
                    break
                }
                withTimeoutOrNull(TICK_MS) { wake.receive() }
            }
        }
    }

    private suspend fun step(t: Long) {
        val b = bridge ?: return
        val tg = target
        val states = b.lights.value
        if (tg == null || tg.level == RoomLightBrightness.OFF || !tg.lights.isSet) {
            val active = activeFor ?: return
            // The option switched off, or the lights set up anew: given back at once; the music
            // ended: dimmed like a pause, and given back after a while.
            if (tg != null || chosen?.containsAll(active) == false) return restore(b)
            // Meanwhile a light someone else switches off or changes is theirs.
            watchForOthers(states, t)
            if (lastTarget?.onlyOn == true) letGoSwitchedOff(states)
            val since = idleSince ?: t.also { idleSince = it }
            if (t - since >= IDLE_RELEASE_MS) return restore(b)
            if (!shownPaused && t - since >= PAUSE_DELAY_MS) lastTarget?.let { send(b, it, t, PAUSE_TRANSITION_MS, force = true, paused = true) }
            return
        }
        idleSince = null
        val wanted = slots(tg.lights)
        // Lights no longer wanted are given back; new ones are saved first, once their states
        // have arrived (they are followed only now), or after a while with what is there.
        if (wanted.keys != activeFor) {
            if (wanted.keys.any { it !in states } && t - followedAt < STATES_WAIT_MS) return
            if (activeFor != null) restore(b)
            activate(b, wanted, states, t, tg.onlyOn)
        }
        // "Leave off" chosen while they glow: the lights switched on for the music go back off
        // (the states read above are old by then: the next tick goes on).
        if (tg.onlyOn && !takenOnlyOn) {
            takenOnlyOn = true
            return giveBackSwitchedOn(b)
        }
        takenOnlyOn = tg.onlyOn
        adopt(b, wanted, states, t, tg.onlyOn)
        watchForOthers(states, t)
        if (tg.onlyOn) letGoSwitchedOff(states)
        val paused = !tg.playing && !tg.landscape
        pausedSince = if (paused) (pausedSince ?: t) else null
        when {
            tg.song != shownSong -> {
                if (tg.song != waitingSong) {
                    waitingSong = tg.song
                    waitingSince = t
                }
                // The first song after taking over is shown at once.
                if (shownSong == null || t - waitingSince >= SETTLE_MS) {
                    shownSong = tg.song
                    shownColors = tg.colors
                    send(b, tg, t, if (lastStepAt == 0L) FIRST_TRANSITION_MS else TRACK_TRANSITION_MS, force = true, paused = paused)
                }
            }
            paused && !shownPaused && t - (pausedSince ?: t) >= PAUSE_DELAY_MS -> send(b, tg, t, PAUSE_TRANSITION_MS, force = true, paused = true)
            !paused && shownPaused -> send(b, tg, t, RESUME_TRANSITION_MS, force = true, paused = false)
            // The landscape's light moves on in long fades, a call per light every while at most.
            tg.colors !== shownColors && (!tg.landscape || t - lastStepAt >= LANDSCAPE_GAP_MS) -> {
                shownColors = tg.colors
                send(b, tg, t, if (tg.landscape) LANDSCAPE_TRANSITION_MS else SOFT_TRANSITION_MS, force = false, paused = shownPaused)
            }
            // A new level or a later hour (a pause is dimmed above, after its delay).
            share(tg, shownPaused) != shownShare -> send(b, tg, t, SOFT_TRANSITION_MS, force = false, paused = shownPaused)
            !paused && !tg.landscape && tg.motion > 0f && t - lastStepAt >= DRIFT_MS -> send(b, tg, t, DRIFT_TRANSITION_MS, force = false, paused = false)
        }
        // Calls that had to wait (a light still busy, a failure to try again): those lights only.
        val due = lights.values.filter { !it.takenOver && !it.inFlight && it.retryAt in 1..t }.mapTo(HashSet()) { it.id }
        if (due.isNotEmpty()) send(b, tg, t, SOFT_TRANSITION_MS, force = true, paused = shownPaused, only = due)
    }

    /**
     * A wanted light whose state arrived only after the others were taken over (or that was
     * unavailable then), or with [Target.onlyOn] one that has been switched on: its state is
     * saved (unless it was saved before in this session), then it joins in at once.
     */
    private suspend fun adopt(b: LightBridge, wanted: Map<String, LightSlot>, states: Map<String, EntityState>, t: Long, onlyOn: Boolean) {
        for ((id, slot) in wanted) {
            if (id in lights) continue
            val s = states[id] ?: continue
            // Turned off for "Leave off" just now: its report may still be on its way (a slow
            // light reads on a while longer). Switched on after that, it joins in again.
            val offAt = givenBack[id]
            if (offAt != null) {
                if (s.state != "off" && t - offAt < GIVEN_BACK_MS) continue
                givenBack.remove(id)
            }
            if (s.state == UNAVAILABLE || (onlyOn && s.state == "off")) continue
            if (id !in saved) save(b, mapOf(id to s))
            lights[id] = Light(id, slot).also {
                it.checkedUpdate = s.lastUpdated
                it.retryAt = t
            }
        }
    }

    /**
     * Saves how the lights are, then takes them over. Lights that were off are switched on dark
     * first, in the new color: never a flash of their old one.
     */
    private suspend fun activate(b: LightBridge, wanted: Map<String, LightSlot>, states: Map<String, EntityState>, t: Long, onlyOn: Boolean) {
        val taken = wanted.keys.mapNotNull { id -> states[id]?.takeIf { it.state != UNAVAILABLE && !(onlyOn && it.state == "off") }?.let { id to it } }.toMap()
        // Taken back within a minute (the screen came back): the earlier snapshot still holds.
        if (t - savedAt > REUSE_SNAPSHOT_MS || !taken.keys.all { it in saved }) {
            saved = emptyMap()
            snapshots = emptySet()
            save(b, taken)
            savedAt = t
        }
        lights.clear()
        givenBack.clear()
        activeFor = wanted.keys.toSet()
        // Followed from now on until they are given back, whatever the target does meanwhile.
        follow()
        takenOnlyOn = onlyOn
        activeLights = true
        for ((id, s) in taken) lights[id] = Light(id, wanted.getValue(id)).also { it.checkedUpdate = s.lastUpdated }
        shownSong = null
        waitingSong = null
        shownPaused = false
        lastStepAt = 0L
    }

    /** Saves [states] in snapshot scenes of Home Assistant's own (one per light), and notes them as a fallback. */
    private suspend fun save(b: LightBridge, states: Map<String, EntityState>) {
        for ((id, s) in states) {
            saved = saved + (id to savedOf(s))
            val data = buildJsonObject {
                put("scene_id", sceneId(id))
                putJsonArray("snapshot_entities") { add(id) }
            }
            if (b.call("scene", "create", null, data) != null) snapshots = snapshots + id
        }
    }

    /**
     * What each light (or each light in [only]) should show, sent where it differs enough from
     * what it shows.
     */
    private fun send(b: LightBridge, target: Target, t: Long, transitionMs: Long, force: Boolean, paused: Boolean, only: Set<String>? = null) {
        // Where the colors will stand when the fade has ended.
        val ft = target.fieldTime() + transitionMs / 1000.0 * target.motion
        val share = share(target, paused)
        for (light in lights.values) {
            if (light.takenOver || (only != null && light.id !in only)) continue
            val state = b.lights.value[light.id]
            if (state == null || state.state == UNAVAILABLE) continue
            val caps = capsOf(state)
            target.colors.color(light.slot, ft, rgb)
            val brightness = LightColor.brightness(share * LightColor.dimFor(rgb))
            val shown = when {
                caps.color -> Shown.Color(LightColor.of(rgb, target.warmth), brightness)
                caps.temperature -> Shown.White(LightColor.kelvin(rgb, target.warmth, caps.minKelvin, caps.maxKelvin), brightness)
                caps.dimmable -> Shown.Level(brightness)
                else -> Shown.On
            }
            val wasOff = state.state == "off"
            // "Leave off": a light that is off stays off.
            if (wasOff && target.onlyOn) continue
            if (shown == Shown.On && !wasOff) continue
            if (small(light.shown, shown) && !force && light.retryAt == 0L) continue
            if (light.inFlight || t - light.sentAt < MIN_GAP_MS) {
                light.retryAt = maxOf(t + 1, light.sentAt + MIN_GAP_MS)
                continue
            }
            light.inFlight = true
            light.retryAt = 0L
            light.sentAt = t
            light.shown = shown
            val preOn = wasOff && caps.dimmable
            light.transitionMs = transitionMs + if (preOn) PRE_ON_MS else 0L
            scope.launch(context) {
                var ctx: String? = null
                try {
                    if (preOn) {
                        // On in the new color, at the very least and at once, then up.
                        remember(light, b.call("light", "turn_on", light.id, turnOn(shown, caps, DARKEST, 0L)))
                        delay(PRE_ON_MS)
                    }
                    ctx = b.call("light", "turn_on", light.id, turnOn(shown, caps, null, transitionMs))
                    remember(light, ctx)
                } finally {
                    light.inFlight = false
                    when {
                        ctx != null -> light.failures = 0
                        // Tried once more; after that the light waits for the next change.
                        ++light.failures <= MAX_RETRIES && !light.takenOver -> light.retryAt = now() + RETRY_MS
                    }
                }
            }
        }
        lastStepAt = t
        shownPaused = paused
        shownShare = share
    }

    /** Whether [next] would not look different from [before]. */
    private fun small(before: Shown?, next: Shown): Boolean = when {
        before is Shown.Color && next is Shown.Color ->
            before.xy.distance(next.xy) < MIN_XY_STEP && abs(before.brightness - next.brightness) < MIN_BRI_STEP
        before is Shown.White && next is Shown.White ->
            abs(before.kelvin - next.kelvin) < MIN_KELVIN_STEP && abs(before.brightness - next.brightness) < MIN_BRI_STEP
        before is Shown.Level && next is Shown.Level -> abs(before.brightness - next.brightness) < MIN_BRI_STEP
        else -> before == next
    }

    /** The share of full brightness for [tg]: the level, the night, a pause. */
    private fun share(tg: Target, paused: Boolean): Float =
        tg.level.brightness * (1f - (1f - NIGHT) * tg.night.coerceIn(0f, 1f)) * (if (paused) PAUSED else 1f)

    private fun remember(light: Light, ctx: String?) {
        if (ctx == null) return
        light.contexts.addLast(ctx)
        while (light.contexts.size > MAX_CONTEXTS) light.contexts.removeFirst()
    }

    /**
     * A light someone else has changed since it settled (switched off, set to white light, made
     * much brighter or darker, or given another color from Home Assistant) is theirs again.
     */
    private fun watchForOthers(states: Map<String, EntityState>, t: Long) {
        for (light in lights.values) {
            if (light.takenOver) continue
            val s = states[light.id] ?: continue
            // Only what changed since the light was taken over (or last judged) counts.
            if (s.lastUpdated <= light.checkedUpdate || s.state == UNAVAILABLE) continue
            val ours = s.context in light.contexts || s.contextParent in light.contexts
            if (ours || s.context == null) {
                light.checkedUpdate = s.lastUpdated
                continue
            }
            // A call of ours on its way: Home Assistant may report what it did before answering,
            // while its context is not known here yet (and it carries our user too).
            if (light.inFlight) continue
            // Klangbild never switches a light off, nor a color light to white: whoever did, it is theirs.
            val shown = light.shown
            if (s.state == "off" || (shown is Shown.Color && s.string("color_mode") == "color_temp")) {
                light.takenOver = true
                continue
            }
            // Brightness and color are judged once the light has settled (a fade reports steps on
            // the way); what a person or an automation did counts at once.
            val byPerson = s.contextUser != null || s.contextParent != null
            if (!byPerson && t - light.sentAt < light.transitionMs + SETTLE_REPORT_MS) continue
            light.checkedUpdate = s.lastUpdated
            val bri = (s.attr("brightness") as? JsonPrimitive)?.intOrNull
            val sentBri = when (shown) {
                is Shown.Color -> shown.brightness
                is Shown.White -> shown.brightness
                is Shown.Level -> shown.brightness
                else -> null
            }
            val changed = (bri != null && sentBri != null && abs(bri - sentBri) > OTHERS_BRI_STEP) ||
                when (shown) {
                    is Shown.Color -> xyOf(s)?.let { it.distance(shown.xy) > (if (byPerson) OTHERS_XY_PERSON else OTHERS_XY) } == true
                    is Shown.White -> (s.attr("color_temp_kelvin") as? JsonPrimitive)?.intOrNull?.let { abs(it - shown.kelvin) > OTHERS_KELVIN } == true
                    else -> false
                }
            if (changed) light.takenOver = true
        }
    }

    /** Gives every light not taken over back as it was ([putBack]), and deletes the snapshot scenes. */
    private suspend fun restore(b: LightBridge?) {
        // A light someone else has changed meanwhile is theirs, also when the app closes.
        if (b != null) watchForOthers(b.lights.value, now())
        // What was saved, as it is now: a new takeover must not change it under the calls.
        val notes = saved
        val scenes = snapshots
        val toRestore = lights.values.filter { !it.takenOver }
        // A light someone else has is saved anew if it is taken over again: its note no longer holds.
        saved = saved - lights.values.filter { it.takenOver }.map { it.id }.toSet()
        lights.values.removeAll { it.takenOver }
        givenBack.clear()
        shownSong = null
        idleSince = null
        if (b != null) {
            putBack(b, toRestore, notes, scenes)
            for (id in scenes) b.call("scene", "delete", "scene.${sceneId(id)}", null)
            snapshots = emptySet()
        } else {
            lights.clear()
        }
        activeFor = null
        activeLights = lights.isNotEmpty()
        // Given back: the lights are followed now only while they are to join in.
        follow()
    }

    /**
     * "Leave off" chosen while the lights glow: the ones that were off before the music go back
     * off; the others keep their colors.
     */
    private suspend fun giveBackSwitchedOn(b: LightBridge) {
        val wereOff = lights.values.filter { !it.takenOver && saved[it.id]?.on == false }
        if (wereOff.isEmpty()) return
        val ids = wereOff.map { it.id }.toSet()
        val scenes = snapshots
        putBack(b, wereOff, saved, scenes)
        for (id in ids.filter { it in scenes }) b.call("scene", "delete", "scene.${sceneId(id)}", null)
        snapshots = snapshots - ids
        saved = saved - ids
        val off = now()
        for (id in ids) givenBack[id] = off
    }

    /**
     * With [Target.onlyOn], a light switched off during the music is let go. Switched on again it
     * joins in anew, and what was saved before it first joined still holds: after the music it
     * goes back to that.
     */
    private fun letGoSwitchedOff(states: Map<String, EntityState>) {
        lights.values.removeAll { !it.inFlight && states[it.id]?.state == "off" }
    }

    /**
     * Gives [toRestore] back as they were, from their snapshot scenes ([scenes]) or else from
     * [notes]: on in their old state, or off. A light that was off is dimmed first, then switched
     * off without a fade (a faded off leaves some lights at their lowest level).
     */
    private suspend fun putBack(b: LightBridge, toRestore: List<Light>, notes: Map<String, Saved>, scenes: Set<String>) {
        // A light leaves the list once it is back as it was: cut short, the rest is given back by
        // whoever comes next.
        val offs = ArrayList<Light>()
        for (light in toRestore) {
            val was = notes[light.id]
            val caps = b.lights.value[light.id]?.let(::capsOf)
            when {
                was == null -> lights.remove(light.id)
                was.on -> {
                    if (light.id !in scenes || !activateScene(b, light.id, caps?.transition == true, RESTORE_TRANSITION_MS)) {
                        b.call("light", "turn_on", light.id, restoreOn(was, caps))
                    }
                    lights.remove(light.id)
                }
                else -> {
                    if (caps?.dimmable == true) {
                        b.call("light", "turn_on", light.id, buildJsonObject {
                            put("brightness", DARKEST)
                            if (caps.transition) put("transition", OFF_FADE_MS / 1000.0)
                        })
                    }
                    offs += light
                }
            }
        }
        if (offs.isNotEmpty()) {
            delay(OFF_FADE_MS + OFF_SETTLE_MS)
            for (light in offs) {
                if (light.id !in scenes || !activateScene(b, light.id, false, 0L)) b.call("light", "turn_off", light.id, null)
                lights.remove(light.id)
            }
        }
    }

    /** Restores [id] from its snapshot scene; whether Home Assistant took the call. */
    private suspend fun activateScene(b: LightBridge, id: String, fade: Boolean, transitionMs: Long): Boolean {
        val data = if (fade) buildJsonObject { put("transition", transitionMs / 1000.0) } else null
        return b.call("scene", "turn_on", "scene.${sceneId(id)}", data) != null
    }

    private fun capsOf(s: EntityState): Caps {
        val modes = (s.attr("supported_color_modes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
        val color = modes.any { it in COLOR_MODES }
        val temperature = "color_temp" in modes
        val features = (s.attr("supported_features") as? JsonPrimitive)?.longOrNull ?: 0L
        return Caps(
            color = color,
            temperature = temperature,
            dimmable = color || temperature || "brightness" in modes || "white" in modes,
            transition = features and TRANSITION_FEATURE != 0L,
            minKelvin = (s.attr("min_color_temp_kelvin") as? JsonPrimitive)?.intOrNull ?: DEFAULT_MIN_KELVIN,
            maxKelvin = (s.attr("max_color_temp_kelvin") as? JsonPrimitive)?.intOrNull ?: DEFAULT_MAX_KELVIN,
        )
    }

    private fun xyOf(s: EntityState): Xy? = (s.attr("xy_color") as? JsonArray)?.let { a ->
        val x = (a.getOrNull(0) as? JsonPrimitive)?.floatOrNull
        val y = (a.getOrNull(1) as? JsonPrimitive)?.floatOrNull
        if (x != null && y != null) Xy(x, y) else null
    }

    private fun savedOf(s: EntityState): Saved {
        fun floats(name: String) = (s.attr(name) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }?.takeIf { it.size == 2 }
        return Saved(
            on = s.state == "on",
            brightness = (s.attr("brightness") as? JsonPrimitive)?.doubleOrNull?.toInt(),
            mode = s.string("color_mode"),
            xy = floats("xy_color"),
            hs = floats("hs_color"),
            kelvin = (s.attr("color_temp_kelvin") as? JsonPrimitive)?.doubleOrNull?.toInt(),
        )
    }

    /** The light's old state as `light.turn_on` data, for when its snapshot scene is gone (Home Assistant restarted). */
    private fun restoreOn(was: Saved, caps: Caps?) = buildJsonObject {
        if (caps?.dimmable != false) was.brightness?.let { put("brightness", it.coerceIn(DARKEST, 255)) }
        when {
            was.mode == "color_temp" && was.kelvin != null -> put("color_temp_kelvin", was.kelvin)
            was.mode == "hs" && was.hs != null -> putJsonArray("hs_color") { was.hs.forEach { add(it) } }
            was.mode in COLOR_MODES && was.xy != null -> putJsonArray("xy_color") { was.xy.forEach { add(it) } }
        }
        if (caps?.transition == true) put("transition", RESTORE_TRANSITION_MS / 1000.0)
    }

    /** [v] to four decimals, as the lights take xy; a color that is none at all (NaN) is 0. */
    private fun fourDecimals(v: Float): Double = (if (v.isNaN()) 0 else (v * 10_000).roundToInt()) / 10_000.0

    /** `light.turn_on` data for [shown] on a light with [caps]; [brightness] overrides the shown one. */
    private fun turnOn(shown: Shown, caps: Caps, brightness: Int?, transitionMs: Long?) = buildJsonObject {
        when (shown) {
            is Shown.Color -> putJsonArray("xy_color") {
                add(fourDecimals(shown.xy.x))
                add(fourDecimals(shown.xy.y))
            }
            is Shown.White -> put("color_temp_kelvin", shown.kelvin)
            else -> Unit
        }
        val level = brightness ?: when (shown) {
            is Shown.Color -> shown.brightness
            is Shown.White -> shown.brightness
            is Shown.Level -> shown.brightness
            Shown.On -> null
        }
        if (caps.dimmable && level != null) put("brightness", level)
        if (caps.transition && transitionMs != null) put("transition", transitionMs / 1000.0)
    }

    /**
     * The lights to keep up with: the chosen ones while they are to join in, and the ones taken
     * over until they are given back (their states say how: a fade, dimmed before off).
     */
    private fun followed(t: Target?): Set<String> =
        (if (t == null || t.level == RoomLightBrightness.OFF) emptySet() else t.lights.ids.toSet()) + activeFor.orEmpty()

    companion object {
        /** Where each light stands: left, behind the middle (its light shows above the screen), right. */
        fun slots(lights: RoomLights): Map<String, LightSlot> = buildMap {
            lights.left?.let { put(it, LightSlot.LEFT) }
            lights.center?.let { if (it !in this) put(it, LightSlot.CENTER) }
            lights.right?.let { if (it !in this) put(it, LightSlot.RIGHT) }
        }

        /** The snapshot scene's id for light [lightId] (a slug, as `scene.create` wants it). */
        fun sceneId(lightId: String): String = "klangbild_" + lightId.substringAfter('.')

        /** The color modes that take a color (https://developers.home-assistant.io/docs/core/entity/light). */
        val COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

        /** `LightEntityFeature.TRANSITION`. */
        const val TRANSITION_FEATURE = 32L

        /** Home Assistant's color temperature range for a light that does not give its own (K). */
        const val DEFAULT_MIN_KELVIN = 2_000
        const val DEFAULT_MAX_KELVIN = 6_535

        const val UNAVAILABLE = "unavailable"

        /** The lowest brightness a light is switched on at before it fades up, or dimmed to before it goes off. */
        const val DARKEST = 2

        const val TICK_MS = 1_000L
        const val MIN_GAP_MS = 1_000L
        const val SETTLE_MS = 1_500L
        const val FIRST_TRANSITION_MS = 2_000L
        const val TRACK_TRANSITION_MS = 2_500L
        const val DRIFT_MS = 10_000L
        const val DRIFT_TRANSITION_MS = 8_000L

        /** The landscape: its light at most this often (per light a call), in fades this long. */
        const val LANDSCAPE_GAP_MS = 20_000L
        const val LANDSCAPE_TRANSITION_MS = 15_000L
        const val SOFT_TRANSITION_MS = 1_500L
        const val PAUSE_DELAY_MS = 2_000L
        const val PAUSE_TRANSITION_MS = 2_000L
        const val RESUME_TRANSITION_MS = 1_000L
        const val RESTORE_TRANSITION_MS = 3_000L
        const val OFF_FADE_MS = 2_000L

        /** After dimming, this long more before the switch off: the fade has surely ended. */
        const val OFF_SETTLE_MS = 300L
        const val PRE_ON_MS = 1_000L
        const val RETRY_MS = 5_000L
        const val MAX_RETRIES = 1

        /** How many of a light's own calls are remembered to tell its changes from anyone else's. */
        const val MAX_CONTEXTS = 12

        /** How long to wait for the lights' states after they are first followed. */
        const val STATES_WAIT_MS = 10_000L

        /** A light turned off for "Leave off" that Home Assistant still reports on is left alone this long. */
        const val GIVEN_BACK_MS = 10_000L
        const val IDLE_RELEASE_MS = 5 * 60_000L
        const val REUSE_SNAPSHOT_MS = 60_000L
        const val SETTLE_REPORT_MS = 2_000L

        /** A pause dims to half; the night to this share of the day's level. */
        const val PAUSED = 0.5f
        const val NIGHT = 0.45f

        /** Changes smaller than these are not sent (they would not be seen). */
        const val MIN_XY_STEP = 0.004f
        const val MIN_BRI_STEP = 5
        const val MIN_KELVIN_STEP = 100

        /** What counts as someone else's change. */
        const val OTHERS_BRI_STEP = 64
        const val OTHERS_XY = 0.08f
        const val OTHERS_XY_PERSON = 0.02f
        const val OTHERS_KELVIN = 400
    }
}
