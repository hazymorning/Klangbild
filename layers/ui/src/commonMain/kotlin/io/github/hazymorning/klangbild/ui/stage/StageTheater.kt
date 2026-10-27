package io.github.hazymorning.klangbild.ui.stage

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
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.RisingText
import io.github.hazymorning.klangbild.ui.TextRise
import io.github.hazymorning.klangbild.ui.playChange
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first

/** How two consecutive stages differ, which decides the choreography. */
internal enum class Change {
    /** Another track with another cover: the new cover fades in over the old one. */
    FULL,

    /** Another track with the same cover (the next track of an album): only the text changes. */
    TEXT,

    /** The same track with a new scene (a better cover) or new metadata: a crossfade. */
    SOFT,

    /** The same track on the other background (light ↔ dark): a slower crossfade, like the light outside. */
    WALL,

    /** The same track and cover with the column arranged anew (its lyrics or the queue's list come or go): only the text changes. */
    COLUMN,

    /** The same track with another queue (an item put in, the order changed): only the list's rows move. */
    ROWS,
    ;

    /** A crossfade of the same track. */
    val isSoft get() = this == SOFT || this == WALL
}

/**
 * The stage with its transitions. On a track change the new cover comes up over the old one in a
 * plain crossfade (the screen never goes dark), while the background's light turns from the old
 * colors to the new. The old text leaves first, and the new lines rise into place one after
 * another, as they do when the lyrics or the queue's list come or go. With the list, a new track
 * moves it up: the rows that stay glide to their new places, the first one goes as the new title
 * comes, and a new row comes at the foot. A pause dims the light, the cover and the text, and
 * nothing moves.
 *
 * Every animated value is read inside draw or layer lambdas, so animations never recompose.
 *
 * @param modelFor builds the model for a stage (title, progress, status and so on).
 * @param intensity the light's strength from the settings (1 = as designed, 0 = off).
 */
@Composable
internal fun StageTheater(
    stage: StageData,
    status: PlayerStatus,
    modelFor: (StageData) -> StageModel,
    modifier: Modifier = Modifier,
    intensity: () -> Float = { 1f },
    /** The connection is lost: the stage dims like a pause. */
    linkDown: Boolean = false,
    /** The URL of the party playing here, while its code shows where the cover stands. */
    partyCode: String? = null,
) {
    var current by remember { mutableStateOf(stage) }
    var previous by remember { mutableStateOf<StageData?>(null) }
    var change by remember { mutableStateOf(Change.FULL) }
    var began by remember { mutableIntStateOf(0) }
    val t = remember { Animatable(1f) }
    val text = remember { RisingText(t) }
    val latest by rememberUpdatedState(stage)

    // One change at a time: what arrives meanwhile waits until the running change has landed
    // (another track makes the running one hurry, see [playChange]), and only the newest follows.
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { target ->
            val old = current
            val trackKey = target.track.key
            val newer: suspend () -> Unit = { snapshotFlow { latest.track.key }.first { it != trackKey } }
            // Lines held by a skip that came to nothing rise the rest of the way first.
            if (trackKey == old.track.key) text.release(newer)
            val kind = changeOf(old, target)
            if (kind == null) {
                current = target
                return@collect
            }
            began++
            t.snapTo(0f)
            when (kind) {
                Change.FULL -> text.begin(TextRise(Motion.TEXT_IN_MS, durationOf(kind)))
                Change.TEXT, Change.COLUMN -> text.begin(TextRise(Motion.TEXT_IN_SAME_COVER_MS, durationOf(kind)))
                else -> Unit
            }
            change = kind
            previous = old
            current = target
            playChange(t, durationOf(kind), newer) { text.hold(it) }
            previous = null
            text.landed()
            if (latest.track.key == trackKey) text.release(newer)
        }
    }

    val cur = current
    val prev = previous
    val dim = rememberStageDim(status, linkDown, cur.scene.appearance)
    val kind = change
    val total = durationOf(kind)
    // A change can begin between composing a frame and drawing it. Until it is composed, the frame
    // shows the stage as the change before left it, its lines where they stood.
    val seen = began
    val at: () -> Float = { if (began == seen) t.value else 1f }
    fun p(startMs: Int, endMs: Int, easing: Easing = Motion.Dimmer) =
        Motion.phaseMs(at(), startMs, endMs, total, easing)

    val curModel = remember(cur, modelFor) { modelFor(cur) }
    val prevModel = remember(prev, modelFor) { prev?.let(modelFor) }
    val coverPx = cur.scene.cover.width
    val party = rememberPartyCode(partyCode, setOfNotNull(cur.scene.appearance, prev?.scene?.appearance), coverPx) { url, wall -> partyCard(url, coverPx, wall) }
    // The slot's room of the stage shown and of the one leaving, kept per stage through a change.
    val queueRooms = remember { QueueRooms() }
    val curQueue = rememberQueuePlan(curModel, queueRooms)
    val prevQueue = prevModel?.let { rememberQueuePlan(it, queueRooms) }

    val lightLevel: () -> Float = { dim.lightLevel * intensity() }
    val coverChanges = prev != null && kind != Change.TEXT && kind != Change.COLUMN && kind != Change.ROWS
    // A new cover comes up over the old one, and the old one goes once it is covered (a station's
    // logo, clear around its mark, lets the old one fade with it there).
    val oldCover: () -> Float = {
        if (kind == Change.FULL) 1f - p(Motion.ART_OUT_START_MS, Motion.ART_OUT_END_MS, Motion.Sine) else 1f
    }
    val newCover: () -> Float = {
        when {
            !coverChanges -> 1f
            kind == Change.FULL -> p(0, Motion.ART_IN_END_MS, Motion.Sine)
            else -> p(0, total, Motion.Sine)
        }
    }
    // The background's light turns from the old colors to the new over the whole change.
    val newGlow: () -> Float = {
        when {
            !coverChanges -> 1f
            kind == Change.FULL -> p(0, Motion.GLOW_MS, Motion.Sine)
            else -> p(0, total, Motion.Sine)
        }
    }
    // The text: the old block leaves first (from where it stood), and the new lines follow one
    // after another. A new look of the same track crossfades in place. On the other background
    // the new lines come up with it (their inks would otherwise stand at once, pale on the old
    // background). The queue's list changing beneath the same track leaves the text as it stands.
    val textIn: (TextLine) -> Float = { line ->
        when {
            began != seen -> text.before(line)
            prev != null && kind == Change.WALL -> text.level(line) * p(0, total, Motion.Sine)
            else -> text.level(line)
        }
    }
    val textRise: (TextLine) -> Float = { line -> Motion.RISE_DP * (1f - if (began != seen) text.before(line) else text.level(line)) }
    val oldLevel: (TextLine) -> Float = { line -> if (kind.isSoft) text.level(line) else text.leaving(line) }
    // The text so far leaves from where it stood. A new look of the same track crossfades in place.
    val textOut: (TextLine) -> Float = { line -> oldLevel(line) * (if (kind.isSoft) 1f - p(0, total) else 1f - p(0, Motion.TEXT_OUT_MS)) }
    val textOutRise: (TextLine) -> Float = { line -> Motion.RISE_DP * (1f - oldLevel(line)) - (if (kind.isSoft) 0f else Motion.LEAVE_RISE_DP * p(0, Motion.TEXT_OUT_MS)) }
    // The queue's rows are the queue, not the track: they come the whole way also when another
    // track hurries a change (the skipped track's own lines stay down), so every change finds
    // them whole and moves them on from there. They glide when the list goes on from the one
    // before (a new track moves it up, the queue changed), else they come and go with the text,
    // as its last line.
    val rowsIn: () -> Float = {
        when {
            began != seen -> 1f
            kind == Change.WALL -> p(0, total, Motion.Sine)
            else -> text.unheld(TextLine.FOOTER)
        }
    }
    val rowsInRise: () -> Float = { if (began != seen) 0f else Motion.RISE_DP * (1f - text.unheld(TextLine.FOOTER)) }
    val rowsOut: () -> Float = { if (kind.isSoft) 1f - p(0, total) else 1f - p(0, Motion.TEXT_OUT_MS) }
    val rowsOutRise: () -> Float = { if (kind.isSoft) 0f else -Motion.LEAVE_RISE_DP * p(0, Motion.TEXT_OUT_MS) }
    // The queue changed beneath the track (with a new look of it too): only the rows move.
    val reordered = kind == Change.ROWS ||
        (kind == Change.SOFT && prevQueue != null && curQueue != null && prevQueue.rows.map { it.id } != curQueue.rows.map { it.id })
    val rowsGlide = prevQueue != null && curQueue != null && (kind == Change.FULL || kind == Change.TEXT || reordered) &&
        prevQueue.rows.any { a -> curQueue.rows.any { it.id == a.id } }
    val rows = when {
        prev == null -> RowsMotion(matched = false, glide = { 1f }, oldAlpha = { 0f }, oldRise = { 0f }, newAlpha = rowsIn, newRise = rowsInRise)
        // Rows that go dissolve first, the others move, and rows put in come once their place is clear.
        reordered -> RowsMotion(
            matched = rowsGlide,
            glide = { p(ROWS_CLEAR_MS, ROWS_SETTLED_MS) },
            oldAlpha = { 1f - p(0, ROWS_CLEAR_MS) },
            oldRise = { 0f },
            newAlpha = { p(if (rowsGlide) ROWS_COME_MS else ROWS_CLEAR_MS, total) },
            newRise = { 0f },
            // A row cover made meanwhile comes in over the whole change, where its row stands.
            lateCoverAlpha = { p(0, total, Motion.Sine) },
        )
        rowsGlide -> RowsMotion(
            matched = true,
            glide = { p(Motion.TEXT_OUT_MS, Motion.TEXT_OUT_MS + ROWS_GLIDE_MS) },
            oldAlpha = rowsOut,
            oldRise = rowsOutRise,
            newAlpha = rowsIn,
            newRise = rowsInRise,
        )
        // On another background the old row covers stay whole under the new ones, as the cover
        // does, where the same rows stand.
        else -> {
            val sameRows = kind == Change.WALL && prevQueue?.rows?.map { it.id } == curQueue?.rows?.map { it.id }
            RowsMotion(
                matched = false,
                glide = { 1f },
                oldAlpha = rowsOut,
                oldRise = rowsOutRise,
                newAlpha = rowsIn,
                newRise = rowsInRise,
                oldCoverAlpha = if (sameRows) ({ 1f }) else rowsOut,
            )
        }
    }

    // The lyrics of the track shown and of the one leaving keep their places through a change.
    val lyricsStates = remember { LyricsStates() }
    SideEffect {
        lyricsStates.keep(cur.lyrics, prev?.lyrics)
        queueRooms.keep(setOfNotNull(cur.scene.key, prev?.scene?.key))
    }

    Box(modifier.fillMaxSize()) {
        // The old background stays whole until the new one (its light and its color beyond the
        // screen's edges) has come up over it, so a new one never shows the old in a strip at the side.
        val oldWall = prev?.scene?.takeIf { coverChanges }
        BaseWall((oldWall ?: cur.scene).palette.wall.asColor())
        if (oldWall != null) {
            WallLayer(oldWall, lightLevel = lightLevel)
            WallMargin(cur.scene.palette.wall.asColor(), alpha = newGlow)
        }
        WallLayer(cur.scene, alpha = newGlow, lightLevel = lightLevel)
        if (prev != null && coverChanges) CoverLayer(prev.scene, alpha = oldCover, coverLevel = { dim.coverLevel })
        CoverLayer(cur.scene, alpha = newCover, coverLevel = { dim.coverLevel })
        // A party's code stands in the cover's place and stays while the tracks change beneath it.
        // When the background changes, its card changes with it.
        val newWall = prev != null && prev.scene.appearance != cur.scene.appearance
        if (newWall) PartyCodeLayer(party.on(prev.scene.appearance), prev.scene, alpha = party.shown, coverLevel = { dim.coverLevel })
        PartyCodeLayer(party.on(cur.scene.appearance), cur.scene, alpha = { party.shown() * if (newWall) newCover() else 1f }, coverLevel = { dim.coverLevel })
        CompositionLocalProvider(LocalLyricsStates provides lyricsStates) {
            // While only the queue's rows move, the text stays as it stands: nothing leaves.
            if (prevModel != null && kind != Change.ROWS) {
                TextLayer(prevModel, alpha = textOut, rise = textOutRise, textLevel = { dim.textLevel }, queuePlan = prevQueue)
            }
            TextLayer(curModel, alpha = textIn, rise = textRise, textLevel = { dim.textLevel }, queuePlan = curQueue)
        }
        QueueRowsLayer(curQueue, prevQueue, rows, textLevel = { dim.textLevel }, coverLevel = { dim.coverLevel })
        // The status line belongs to the room, not to the track, so it stays. On the other
        // background its inks crossfade with the stage.
        if (prevModel != null && kind == Change.WALL) {
            StatusLayer(prevModel, alpha = { 1f - p(0, total, Motion.Sine) })
            StatusLayer(curModel, alpha = { p(0, total, Motion.Sine) })
        } else {
            StatusLayer(curModel)
        }
    }
}

/**
 * How [new] differs from [old], the stage on screen. Null when only what the stage doesn't draw
 * has changed (another artwork URL for the same cover), so there is nothing to watch.
 */
internal fun changeOf(old: StageData, new: StageData): Change? {
    if (new.scene === old.scene && new.lyrics === old.lyrics && new.queue == old.queue && drawnAlike(new.track, old.track)) return null
    if (new.track.key != old.track.key) {
        // Only the text changes for the next track of the same cover, unless the background changes with it.
        return if (sameCover(old.scene, new.scene) && new.scene.appearance == old.scene.appearance) Change.TEXT else Change.FULL
    }
    return when {
        new.scene.appearance != old.scene.appearance -> Change.WALL
        new.scene !== old.scene -> Change.SOFT
        new.lyrics !== old.lyrics || (new.queue == null) != (old.queue == null) -> Change.COLUMN
        new.queue != old.queue && drawnAlike(new.track, old.track) -> Change.ROWS
        else -> Change.SOFT
    }
}

private fun durationOf(change: Change) = when (change) {
    Change.FULL -> Motion.TRACK_CHANGE_MS
    Change.TEXT, Change.COLUMN -> Motion.TEXT_CHANGE_MS
    Change.SOFT -> Motion.SOFT_SWAP_MS
    Change.WALL -> Motion.WALL_CHANGE_MS
    Change.ROWS -> Motion.QUEUE_CHANGE_MS
}

/** How long the rows take to glide to their new places, once the old text has left (ms). */
private const val ROWS_GLIDE_MS = 600

/** When the queue changes beneath a track, the rows that go have gone after this long (ms). */
private const val ROWS_CLEAR_MS = 300

/** The rows that stay move until this time in such a change (ms). */
private const val ROWS_SETTLED_MS = 750

/** The rows put in come from this time on, when their place is nearly clear (ms). */
private const val ROWS_COME_MS = 650

/**
 * Whether the stage draws [a] and [b] alike: the cover comes with the scene, so another artwork
 * URL, or what only the quick settings and the queue read, changes nothing on screen.
 */
internal fun drawnAlike(a: TrackInfo, b: TrackInfo): Boolean =
    a == b.copy(artworkUrl = a.artworkUrl, artworkFallbackUrl = a.artworkFallbackUrl, favorite = a.favorite, songPicture = a.songPicture, queueItemId = a.queueItemId)

/** Whether [a] and [b] show the same cover (same shape and thumbnail), as consecutive tracks of one album do. */
internal fun sameCover(a: ArtworkScene, b: ArtworkScene): Boolean =
    a.shape == b.shape && a.coverGenerated == b.coverGenerated && a.thumb.contentEquals(b.thumb)
