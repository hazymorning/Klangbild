package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.FlowEntry
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.render.Balance
import io.github.hazymorning.klangbild.render.FlowCard
import io.github.hazymorning.klangbild.render.FlowCards
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.RisingText
import io.github.hazymorning.klangbild.ui.TextRise
import io.github.hazymorning.klangbild.ui.playChange
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.stage.LocalLyricsStates
import io.github.hazymorning.klangbild.ui.stage.LyricsStates
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StatusLayer
import io.github.hazymorning.klangbild.ui.stage.TextLayer
import io.github.hazymorning.klangbild.ui.stage.TextLine
import io.github.hazymorning.klangbild.ui.stage.WallLayer
import io.github.hazymorning.klangbild.ui.stage.WallMargin
import io.github.hazymorning.klangbild.ui.stage.asColor
import io.github.hazymorning.klangbild.ui.stage.drawnAlike
import io.github.hazymorning.klangbild.ui.stage.partyCard
import io.github.hazymorning.klangbild.ui.stage.rememberPartyCode
import io.github.hazymorning.klangbild.ui.stage.rememberStageDim
import io.github.hazymorning.klangbild.ui.stage.sameCover
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How two consecutive strips differ, which decides the choreography. */
internal enum class FlowChange {
    /** Another card came to the middle: the strip turns over. */
    MOVE,

    /** The next song of the same picture: the text changes (the picture only if it differs after all). */
    TEXT,

    /** Only cards beside the middle came or went (the queue changed). */
    SIDES,

    /** Same track, new scene (a better cover) or described anew: a quiet cross-fade. */
    SOFT,

    /** Same track on the other wall (light ↔ dark): the same, slower. */
    WALL,

    /** Same track and picture, other words in the column (its lyrics come or go): only the text changes. */
    LYRICS,
    ;

    /** A cross-fade of the same track. */
    val isSoft get() = this == SOFT || this == WALL

    /** New text comes: the old lines leave and the new ones rise at their own places. */
    val newText get() = this == MOVE || this == TEXT || this == LYRICS
}

/**
 * When the parts of a change happen (ms): cards that make way dissolve first ([clearMs], 0
 * when none have to), then the strip turns ([turnMs]); the whole change lasts [totalMs]. The
 * composition moves to its new balance with the turn, or first, while nothing else moves
 * ([roomFirst]), when the text column has to make room for cards that come. New text rises as
 * [rise] says.
 */
private class Timeline(val clearMs: Int, val turnMs: Int, val totalMs: Int, val roomFirst: Boolean = false, val rise: TextRise? = null) {
    val clear = Window(0f, clearMs / totalMs.toFloat())
    val turn = Window(clearMs / totalMs.toFloat(), (clearMs + turnMs) / totalMs.toFloat())
    val settle = if (roomFirst) clear else turn
}

/** A change's choreography: each card's way and the timeline they follow. */
private class Choreography(val motions: List<CardMotion>, val timeline: Timeline)

/**
 * Cover Flow with its transitions. When another picture comes to the middle, the strip turns
 * over in one unhurried, eased movement: the leaving cover swings away to the side as the next
 * one turns to face the room. The old text leaves first, the new lines rise as the new cover
 * arrives (as they do when the song's words come or go, the cards standing still), and the
 * light above the cards takes its color. New cards roll in opaque from beyond
 * the end of the strip. Cards that are no longer wanted dissolve first, before anything moves
 * behind them ([choreograph]), so two covers are never on top of each other. A pause dims the light,
 * the cards and the text; nothing moves.
 *
 * Where cards are missing (the first song, the last, a player without a queue), what is there
 * stands with the text in the middle of the screen ([FlowGeometry.balance]). When that changes,
 * the composition moves with the strip's own turn: lines that leave go from where they stood,
 * new ones rise at the new place, and what stays moves along.
 *
 * One change at a time: what arrives meanwhile waits until the running change has landed. If
 * that is another song, the running change hurries, and the lines of a song that is skipped
 * past never come up ([RisingText]).
 *
 * Every animated value is read inside draw/layer lambdas, so animations never recompose.
 */
@Composable
internal fun FlowTheater(
    flow: FlowData,
    status: PlayerStatus,
    modelFor: (StageData) -> StageModel,
    modifier: Modifier = Modifier,
    intensity: () -> Float = { 1f },
    /** The connection is lost: the stage dims like a pause. */
    linkDown: Boolean = false,
    /** The link of the party playing here, while its code shows in the middle card's place. */
    partyCode: String? = null,
) {
    var current by remember { mutableStateOf(flow) }
    var previous by remember { mutableStateOf<FlowData?>(null) }
    var change by remember { mutableStateOf(FlowChange.MOVE) }
    var newLook by remember { mutableStateOf(false) }
    var motions by remember { mutableStateOf(cardsAtRest(flow)) }
    var timeline by remember { mutableStateOf(Timeline(0, Motion.FLOW_TURN_MS, Motion.FLOW_TURN_MS)) }
    var began by remember { mutableIntStateOf(0) }
    val t = remember { Animatable(1f) }
    val text = remember { RisingText(t) }
    val latest by rememberUpdatedState(flow)

    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { target ->
            val old = current
            val song = target.middle.track.key
            // Only another song hurries a change; a card that joins the strip waits for it to land.
            val newer: suspend () -> Unit = { snapshotFlow { latest.middle.track.key }.first { it != song } }
            // Lines held by a skip that came to nothing rise the rest of the way first.
            if (song == old.middle.track.key) text.release(newer)
            if (target == old) return@collect
            val kind = changeOf(old, target)
            val look = takesNewLook(old, target, kind)
            val choreography = choreograph(old, target, kind, FlowGeometry(target.middle.scene.layout), look)
            if (kind == FlowChange.SIDES && choreography.motions.all { it.from == it.to && it.fromAlpha == it.toAlpha }) {
                // Only a card's scene was renewed (the same picture in the same light): nothing to watch.
                current = target
                motions = choreography.motions
                return@collect
            }
            began++
            t.snapTo(0f)
            choreography.timeline.rise?.let { text.begin(it) }
            change = kind
            newLook = look
            timeline = choreography.timeline
            motions = choreography.motions
            previous = old
            current = target
            playChange(t, choreography.timeline.totalMs, newer) { text.hold(it) }
            previous = null
            text.landed()
            motions = cardsAtRest(target)
            if (latest.middle.track.key == song) text.release(newer)
        }
    }

    val middle = current.middle
    val appearance = middle.scene.appearance
    val dim = rememberStageDim(status, linkDown, appearance)

    val prev = previous
    val kind = change
    val timing = timeline
    val total = timing.totalMs
    // A change can begin between composing a frame and drawing it. Until it is composed, the frame
    // shows the stage as the change before left it, its lines where they stood.
    val seen = began
    val at: () -> Float = { if (began == seen) t.value else 1f }
    fun p(startMs: Int, endMs: Int, easing: Easing = Motion.Dimmer) =
        Motion.phaseMs(at(), startMs, endMs, total, easing)

    val curStage = current.stage
    val prevStage = prev?.stage
    val curModel = remember(curStage, modelFor) { modelFor(curStage) }
    val prevModel = remember(prevStage, modelFor) { prevStage?.let(modelFor) }
    val geometry = remember(middle.scene.layout) { FlowGeometry(middle.scene.layout) }
    val targetBalance = remember(current, geometry) { balanceOf(current, geometry) }
    val fromBalance = remember(prev, geometry) { prev?.let { balanceOf(it, geometry) } ?: targetBalance }
    val balance: () -> Balance = {
        if (prev == null) targetBalance else fromBalance.towards(targetBalance, Motion.Turn.transform(timing.settle.of(at())))
    }
    // Lines that leave and rise anew keep to their own places; lines that stay move along.
    val newColumn: () -> Float = { if (prev != null && kind.newText) targetBalance.column else balance().column }
    val oldColumn: () -> Float = { if (kind.newText) fromBalance.column else balance().column }
    val wall = middle.scene.palette.wall.asColor()
    val lightLevel: () -> Float = { dim.lightLevel * intensity() }
    // The strip takes a new look (a better cover, the other wall): each card goes over from its
    // texture before to its new one. A song only described anew changes its text alone.
    val restrip = prev != null && newLook
    val oldStrip = prev?.middle?.scene?.takeIf { restrip }
    val party = rememberPartyCode(partyCode, setOfNotNull(appearance, oldStrip?.appearance), geometry) { url, wall -> partySign(url, wall, geometry) }
    val newWall: () -> Float = {
        when {
            prev == null -> 1f
            kind == FlowChange.MOVE -> p(timing.clearMs, timing.clearMs + Motion.FLOW_LIGHT_MS, Motion.Sine)
            restrip -> p(0, total, Motion.Sine)
            else -> 1f
        }
    }
    val textIn: (TextLine) -> Float = { line ->
        when {
            began != seen -> text.before(line)
            prev != null && kind.isSoft -> text.level(line) * p(0, total, Motion.Sine)
            else -> text.level(line)
        }
    }
    val textRise: (TextLine) -> Float = { line -> Motion.RISE_DP * (1f - if (began != seen) text.before(line) else text.level(line)) }
    // The text so far leaves from where it stood; a new look of the same track crossfades in place.
    val oldLevel: (TextLine) -> Float = { line -> if (kind.isSoft) text.level(line) else text.leaving(line) }

    // The words of the song shown and of the one leaving keep their places through a change.
    val lyricsStates = remember { LyricsStates() }
    SideEffect { lyricsStates.keep(curStage.lyrics, prevStage?.lyrics) }

    Box(modifier.fillMaxSize()) {
        // As on the single stage: the old wall stays whole until the new one has come up over it.
        val oldWall = prev?.middle?.scene?.takeIf { kind == FlowChange.MOVE || restrip }
        BaseWall(oldWall?.palette?.wall?.asColor() ?: wall)
        if (oldWall != null) {
            WallLayer(oldWall, lightLevel = lightLevel, shift = { balance().cards }, column = { balance().column })
            WallMargin(wall, alpha = newWall)
        }
        WallLayer(middle.scene, alpha = newWall, lightLevel = lightLevel, shift = { balance().cards }, column = { balance().column })
        // One strip, also while it takes a new look: each card is the mix of its texture before
        // and its new one, so a card never shows through another and its mirror image keeps its
        // strength.
        val before = remember(oldStrip, party.on(oldStrip?.appearance ?: appearance)) {
            oldStrip?.let { FlowLook(it.appearance, it.palette.wall.asColor(), !it.coverField, party.on(it.appearance)) }
        }
        FlowStrip(
            cards = motions,
            geometry = geometry,
            appearance = appearance,
            wall = wall,
            time = at,
            coverLevel = { dim.coverLevel },
            edges = !middle.scene.coverField,
            shift = { balance().cards },
            sign = party.on(appearance),
            signAlpha = party.shown,
            before = before,
            look = { if (restrip) p(0, total, Motion.Sine) else 1f },
        )
        CompositionLocalProvider(LocalLyricsStates provides lyricsStates) {
            if (prevModel != null && kind != FlowChange.SIDES) {
                TextLayer(
                    prevModel,
                    alpha = { line -> oldLevel(line) * (if (kind.isSoft) 1f - p(0, total) else 1f - p(0, Motion.TEXT_OUT_MS)) },
                    rise = { line -> Motion.RISE_DP * (1f - oldLevel(line)) - (if (kind.isSoft) 0f else Motion.LEAVE_RISE_DP * p(0, Motion.TEXT_OUT_MS)) },
                    textLevel = { dim.textLevel },
                    shift = oldColumn,
                )
            }
            TextLayer(curModel, alpha = textIn, rise = textRise, textLevel = { dim.textLevel }, shift = newColumn)
        }
        // On the other wall the status line's inks cross-fade with the stage.
        if (prevModel != null && kind == FlowChange.WALL) {
            StatusLayer(prevModel, shift = { balance().column }, alpha = { 1f - p(0, total, Motion.Sine) })
            StatusLayer(curModel, shift = { balance().column }, alpha = { p(0, total, Motion.Sine) })
        } else {
            StatusLayer(curModel, shift = { balance().column })
        }
    }
}

/**
 * A party's code as a card of the strip for [wall]: facing the room in the middle card's place,
 * with its mirror image on the dark gloss.
 */
private fun partySign(url: String, wall: Appearance, geometry: FlowGeometry): FlowCard? {
    val layout = geometry.layout
    val density = layout.screen.density
    val card = partyCard(url, (layout.art * density).roundToInt(), wall) ?: return null
    return FlowCards.build(card, cornerPx = layout.radius * density, geometry = geometry, density = density, appearance = wall)
}

/**
 * Whether the strip takes a new look from [old] to [new] in a change of [kind]: a better cover or
 * the other background for the same track (unless the cards stay as they were), or the next track
 * of the same card whose cover or background differs.
 */
internal fun takesNewLook(old: FlowData, new: FlowData, kind: FlowChange): Boolean = when (kind) {
    FlowChange.SOFT, FlowChange.WALL -> !sameCards(old, new)
    FlowChange.TEXT -> !sameCover(old.middle.scene, new.middle.scene) || old.middle.scene.appearance != new.middle.scene.appearance
    else -> false
}

/** Whether [a] and [b] show the same cards in the same places, each with the same picture. */
private fun sameCards(a: FlowData, b: FlowData): Boolean =
    a.current == b.current && a.cards.size == b.cards.size &&
        a.cards.indices.all { a.cards[it].id == b.cards[it].id && a.cards[it].scene === b.cards[it].scene }

internal fun changeOf(old: FlowData, new: FlowData): FlowChange {
    val a = old.middle
    val b = new.middle
    return when {
        a.id != b.id -> FlowChange.MOVE
        a.track.key != b.track.key -> FlowChange.TEXT
        a.scene !== b.scene && a.scene.appearance != b.scene.appearance -> FlowChange.WALL
        a.scene !== b.scene -> FlowChange.SOFT
        old.lyrics !== new.lyrics -> FlowChange.LYRICS
        // The same song described anew (a year that came late): its text cross-fades, as on the single stage.
        !drawnAlike(a.track, b.track) -> FlowChange.SOFT
        else -> FlowChange.SIDES
    }
}

/** Where [flow]'s composition stands with the cards it has beside the middle one. */
internal fun balanceOf(flow: FlowData, geometry: FlowGeometry): Balance =
    geometry.balance(left = flow.current, right = flow.cards.size - flow.current - 1)

/** The cards of [flow] standing still, the middle one at position 0. */
private fun cardsAtRest(flow: FlowData): List<CardMotion> = flow.cards.mapIndexed { i, e ->
    val p = (i - flow.current).toFloat()
    CardMotion(e.card, p, p)
}

/** The entry's card: every scene of Cover Flow has one. */
private val FlowEntry.card: FlowCard get() = checkNotNull(scene.card) { "A Cover Flow scene has a card" }

/**
 * Each card's way from [old] to [new]. The strip moves away from the side new pictures come
 * from (the right going forward, the left going back), and on that side:
 *
 * - cards that arrive roll in opaque from beyond the end of the strip, in formation, the first
 *   one from [FlowGeometry.EDGE];
 * - cards that leave dissolve first, before anything moves behind them, so two covers are never
 *   on top of each other;
 * - if the order there does not simply continue (a song put in between, the queue rearranged),
 *   the whole side is replaced: what stood there dissolves, and all of its cards roll in.
 *
 * Cards in both strips move from their old place to their new one. On the other side, cards
 * that leave step one place farther out as they fade, and cards that appear fade in where they
 * stand.
 *
 * The text column never passes over a card: where the composition's new balance moves it right,
 * it makes room first and the cards follow; where it moves left, the cards leaving on the
 * screen's right go first.
 *
 * When the strip takes a [newLook], a card in both strips goes over from its texture before to
 * its new one. In a new look of the same song (a better cover, the other wall) nothing rolls or
 * moves: cards that come, go or change places fade where they stand.
 */
private fun choreograph(old: FlowData, new: FlowData, kind: FlowChange, geometry: FlowGeometry, newLook: Boolean): Choreography {
    val oldPos = HashMap<Long, Float>()
    old.cards.forEachIndexed { i, e -> oldPos[e.id] = (i - old.current).toFloat() }
    val oldCards = old.cards.associate { it.id to it.card }
    val newPos = HashMap<Long, Float>()
    new.cards.forEachIndexed { i, e -> newPos[e.id] = (i - new.current).toFloat() }
    val oldMiddle = old.middle.id
    // Forward unless the old middle card now stands to the right. Positions below are taken in
    // the strip's direction: new pictures arrive from the positive side.
    val dir = if ((newPos[oldMiddle] ?: -1f) <= 0f) 1f else -1f
    fun was(id: Long) = oldPos[id]?.times(dir)
    fun now(id: Long) = newPos.getValue(id) * dir
    // How far the cards that stay move: one place when the strip turns over, none when only its sides change.
    val shift = newPos[oldMiddle]?.times(dir) ?: -1f
    val arriving = new.cards.filter { it.id != oldMiddle && now(it.id) >= 0f }
    val staying = arriving.filter { it.id in oldPos }
    val lastStaying = staying.maxOfOrNull { now(it.id) } ?: Float.NEGATIVE_INFINITY
    val continues = staying.all { was(it.id)!! > 0f && now(it.id) == was(it.id)!! + shift } &&
        arriving.all { it.id in oldPos || now(it.id) > lastStaying }
    val soft = kind.isSoft
    val rolling = if (soft) HashSet() else (if (continues) arriving.filter { it.id !in oldPos } else arriving).mapTo(HashSet()) { it.id }
    // How far the arriving cards roll: the first one (nearest the middle) starts just out of sight.
    val run = arriving.filter { it.id in rolling }.minOfOrNull { now(it.id) }?.let { FlowGeometry.EDGE - it }
    // Cards on the arriving side that make way (they leave, or roll in again).
    val clearing = old.cards.any { e -> was(e.id)!! > 0f && (e.id !in newPos || e.id in rolling) }
    // Where the text column goes: it moves right first, to make room; it moves left only once
    // the cards on the screen's right that leave have gone.
    val column = balanceOf(new, geometry).column - balanceOf(old, geometry).column
    val roomFirst = column > COLUMN_STILL_DP && new.cards.any { e -> newPos.getValue(e.id) > 0f && (e.id !in oldPos || e.id in rolling) }
    val leaveFirst = column < -COLUMN_STILL_DP && old.cards.any { e -> oldPos.getValue(e.id) > 0f && (e.id !in newPos || e.id in rolling) }

    val turnMs = when (kind) {
        FlowChange.MOVE -> Motion.FLOW_TURN_MS
        FlowChange.TEXT, FlowChange.LYRICS -> Motion.TEXT_CHANGE_MS
        FlowChange.SIDES -> Motion.QUEUE_CHANGE_MS
        FlowChange.SOFT -> Motion.SOFT_SWAP_MS
        FlowChange.WALL -> Motion.WALL_CHANGE_MS
    }
    // Something rolls in where cards must make way: they go first; and so does the room the text
    // column makes, or the cards it moves into.
    val clearMs = if (!soft && ((run != null && clearing) || roomFirst || leaveFirst)) Motion.FLOW_CLEAR_MS else 0
    // New text rises as the new cover turns to face the room, or sooner when only the text changes.
    val riseMs = when (kind) {
        FlowChange.MOVE -> clearMs + Motion.FLOW_TEXT_IN_MS
        FlowChange.TEXT, FlowChange.LYRICS -> Motion.TEXT_IN_SAME_COVER_MS
        else -> null
    }
    val totalMs = max(clearMs + turnMs, riseMs?.let { TextRise.endMs(it) } ?: 0)
    val timeline = Timeline(clearMs, turnMs, totalMs, roomFirst = roomFirst && !soft, rise = riseMs?.let { TextRise(it, totalMs) })
    val turn = timeline.turn

    val out = ArrayList<CardMotion>(new.cards.size + old.cards.size)
    for (e in new.cards) {
        val card = e.card
        val to = now(e.id)
        val from = was(e.id)
        out += when {
            e.id in rolling -> CardMotion(card, dir * (to + run!!), dir * to, move = turn, rolls = true)
            from != null && (!soft || from == to) -> CardMotion(card, dir * from, dir * to, move = turn, was = oldCards.getValue(e.id).takeIf { newLook && it !== card })
            else -> CardMotion(card, dir * to, dir * to, fromAlpha = 0f, fade = turn)
        }
    }
    for (e in old.cards) {
        val moved = soft && e.id in newPos && was(e.id) != now(e.id)
        if (e.id in newPos && e.id !in rolling && !moved) continue
        val card = e.card
        val from = was(e.id)!!
        // A card on the screen's right that leaves before the text column moves into its place.
        val beforeColumn = leaveFirst && oldPos.getValue(e.id) > 0f
        out += if (from > 0f || beforeColumn || soft) {
            // Makes way where it stands, first if something rolls in behind it. The farther
            // cards go a little sooner, so two leaving cards do not show through each other.
            val clear = timeline.clear
            val fade = if (clearMs > 0) Window(0f, clear.end * (1f - FARTHER_FIRST * (abs(from) - 1f).coerceIn(0f, 1f))) else turn
            CardMotion(card, dir * from, dir * from, toAlpha = 0f, fade = fade)
        } else {
            CardMotion(card, dir * from, dir * (from - 1f), toAlpha = 0f, move = turn, fade = turn)
        }
    }
    return Choreography(out, timeline)
}

/** Smaller moves of the text column count as none (dp). */
private const val COLUMN_STILL_DP = 0.5f

/** Of the cards that make way, each one a place farther out is gone this share of the clearing sooner. */
private const val FARTHER_FIRST = 0.35f
