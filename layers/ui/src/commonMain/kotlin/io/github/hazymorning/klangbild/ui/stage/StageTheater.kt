package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
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
enum class Change {
    /** New track with a new picture: the new artwork settles over the old one. */
    FULL,

    /** New track, same picture (the next song of an album): only the text changes. */
    TEXT,

    /** Same track, new scene (a better cover) or described anew: a quiet cross-fade. */
    SOFT,

    /** Same track on the other wall (light ↔ dark): the same cross-fade, slower, like the light outside. */
    WALL,

    /** Same track and picture, another arrangement of the column (its words or the queue's list come or go): only the text changes. */
    COLUMN,

    /** Same track, the queue's list has changed (a song put in, the order changed): only its rows move. */
    ROWS,
    ;

    /** A cross-fade of the same track. */
    val isSoft get() = this == SOFT || this == WALL
}

/**
 * The stage with its transitions. On a track change the new artwork comes up over the old one
 * in a plain cross-fade (the screen never goes dark), while the light on the wall turns from
 * the old colors to the new. The old text leaves first; the new lines rise into place one
 * after another, and so they do when the song's words or the queue's list come or go. With the
 * list, a new song moves it up: the rows that stay glide to their new places, the first one
 * goes as the new title comes, and a new row comes at the foot. A pause dims light, artwork and
 * text; nothing moves.
 *
 * Every animated value is read inside draw/layer lambdas, so animations never recompose.
 *
 * @param modelFor builds the model for a stage (title, progress, status and so on).
 * @param intensity the light's strength from the settings (1 = as designed, 0 = off).
 */
@Composable
fun StageTheater(
    stage: StageData,
    status: PlayerStatus,
    modelFor: (StageData) -> StageModel,
    modifier: Modifier = Modifier,
    intensity: () -> Float = { 1f },
    /** The link is down: the stage dims like a pause. */
    dimmed: Boolean = false,
    /** The link of the party playing here, while its code shows where the cover stands. */
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
    // (another song makes the running one hurry, see [playChange]), and only the newest follows.
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { target ->
            val old = current
            val song = target.track.key
            val newer: suspend () -> Unit = { snapshotFlow { latest.track.key }.first { it != song } }
            // Lines held by a skip that came to nothing rise the rest of the way first.
            if (song == old.track.key) text.release(newer)
            val kind = changeOf(old, target)
            if (kind == null) {
                current = target
                return@collect
            }
            began++
            t.snapTo(0f)
            when (kind) {
                Change.FULL -> text.begin(TextRise(Motion.TEXT_IN_MS, durationOf(kind)))
                Change.TEXT, Change.COLUMN -> text.begin(TextRise(Motion.TEXT_IN_SAME_PICTURE_MS, durationOf(kind)))
                else -> Unit
            }
            change = kind
            previous = old
            current = target
            playChange(t, durationOf(kind), newer) { text.hold(it) }
            previous = null
            text.landed()
            if (latest.track.key == song) text.release(newer)
        }
    }

    val dim = rememberStageDim(status, dimmed, stage.scene.appearance)

    val cur = current
    val prev = previous
    val kind = change
    val total = durationOf(kind)
    // A change can begin between composing a frame and drawing it. Until it is composed, the frame
    // shows the stage as the change before left it, its lines where they stood.
    val seen = began
    val at: () -> Float = { if (began == seen) t.value else 1f }
    fun p(startMs: Int, endMs: Int, easing: androidx.compose.animation.core.Easing = Motion.Dimmer) =
        Motion.phaseMs(at(), startMs, endMs, total, easing)

    val curModel = remember(cur, modelFor) { modelFor(cur) }
    val prevModel = remember(prev, modelFor) { prev?.let(modelFor) }
    val px = cur.scene.cover.width
    val party = rememberPartyCode(partyCode, setOfNotNull(cur.scene.appearance, prev?.scene?.appearance), px) { url, wall -> partyCard(url, px, wall) }
    val curPlan = rememberTextPlan(curModel)
    val prevPlan = prevModel?.let { rememberTextPlan(it) }
    // The bar's room of the stage shown and of the one leaving, kept by stage through a change.
    val queueRooms = remember { QueueRooms() }
    val curQueue = rememberQueuePlan(curModel, queueRooms)
    val prevQueue = prevModel?.let { rememberQueuePlan(it, queueRooms) }

    val level: () -> Float = { dim.light * intensity() }
    val pictureChanges = prev != null && kind != Change.TEXT && kind != Change.COLUMN && kind != Change.ROWS
    // A new picture comes up over the old one; the old goes once it is covered (a station's logo,
    // clear around its mark, lets the old one fade with it there).
    val oldPicture: () -> Float = {
        if (kind == Change.FULL) 1f - p(Motion.ART_OUT_START_MS, Motion.ART_OUT_END_MS, Motion.Sine) else 1f
    }
    val newPicture: () -> Float = {
        when {
            !pictureChanges -> 1f
            kind == Change.FULL -> p(0, Motion.ART_IN_END_MS, Motion.Sine)
            else -> p(0, total, Motion.Sine)
        }
    }
    // The light on the wall turns from the old colors to the new over the whole change.
    val newGlow: () -> Float = {
        when {
            !pictureChanges -> 1f
            kind == Change.FULL -> p(0, Motion.GLOW_MS, Motion.Sine)
            else -> p(0, total, Motion.Sine)
        }
    }
    // The text: the old block leaves first (from where it stood), the new lines follow one after
    // another; a new look of the same song cross-fades in place. On the other wall the new lines
    // come up with the wall (their inks would otherwise stand at once, pale on the old paper).
    // The queue's list changing beneath the same song leaves the text as it stands.
    val textIn: (TextLine) -> Float = { line ->
        when {
            began != seen -> text.before(line)
            prev != null && kind == Change.WALL -> text.level(line) * p(0, total, Motion.Sine)
            prev != null && kind == Change.ROWS -> 1f
            else -> text.level(line)
        }
    }
    val textRise: (TextLine) -> Float = { line ->
        when {
            began != seen -> 10f * (1f - text.before(line))
            text.rising -> 10f * (1f - text.level(line))
            else -> 0f
        }
    }
    val stood: (TextLine) -> Float = { line -> if (kind.isSoft) text.level(line) else text.leaving(line) }
    // The text so far leaves from where it stood; a new look of the same song cross-fades in place.
    val textOut: (TextLine) -> Float = { line ->
        when (kind) {
            Change.ROWS -> 0f
            else -> stood(line) * (if (kind.isSoft) 1f - p(0, total) else 1f - p(0, Motion.TEXT_OUT_MS))
        }
    }
    val textOutRise: (TextLine) -> Float = { line -> 10f * (1f - stood(line)) - (if (kind.isSoft || kind == Change.ROWS) 0f else 6f * p(0, Motion.TEXT_OUT_MS)) }
    // The queue's rows are the queue, not the song: they come the whole way also when another song
    // hurries a change (the skipped song's own lines stay down), so every change finds them whole
    // and moves them on from there. They glide when the list goes on from the one before (a new
    // song moves it up, the queue changed), else they come and go with the text, as its last line.
    val rowsIn: () -> Float = {
        when {
            began != seen -> 1f
            kind == Change.WALL -> p(0, total, Motion.Sine)
            else -> text.unheld(TextLine.NEXT)
        }
    }
    val rowsInRise: () -> Float = { if (began != seen) 0f else 10f * (1f - text.unheld(TextLine.NEXT)) }
    val rowsOut: () -> Float = { if (kind.isSoft) 1f - p(0, total) else 1f - p(0, Motion.TEXT_OUT_MS) }
    val rowsOutRise: () -> Float = { if (kind.isSoft) 0f else -6f * p(0, Motion.TEXT_OUT_MS) }
    // The queue changed beneath the song (with a new look of it too): only the rows move.
    val reordered = kind == Change.ROWS ||
        (kind == Change.SOFT && prevQueue != null && curQueue != null && prevQueue.rows.map { it.id } != curQueue.rows.map { it.id })
    val rowsGo = prevQueue != null && curQueue != null && (kind == Change.FULL || kind == Change.TEXT || reordered) &&
        prevQueue.rows.any { a -> curQueue.rows.any { it.id == a.id } }
    val rows = when {
        prev == null -> RowsMotion(false, { 1f }, { 0f }, { 0f }, rowsIn, rowsInRise)
        // Rows that go dissolve first, the others move, and rows put in come once their place is clear.
        reordered -> RowsMotion(
            matched = rowsGo,
            glide = { p(ROWS_CLEAR_MS, ROWS_SETTLED_MS) },
            old = { 1f - p(0, ROWS_CLEAR_MS) },
            oldRise = { 0f },
            new = { p(if (rowsGo) ROWS_COME_MS else ROWS_CLEAR_MS, total) },
            newRise = { 0f },
            // A small cover made meanwhile comes in over the whole change, where its row stands.
            cover = { p(0, total, Motion.Sine) },
        )
        rowsGo -> RowsMotion(
            matched = true,
            glide = { p(Motion.TEXT_OUT_MS, Motion.TEXT_OUT_MS + ROWS_GLIDE_MS) },
            old = rowsOut,
            oldRise = rowsOutRise,
            new = rowsIn,
            newRise = rowsInRise,
        )
        // On another wall the old small covers stay whole under the new ones, as the cover does,
        // where the same rows stand.
        else -> {
            val sameRows = kind == Change.WALL && prevQueue?.rows?.map { it.id } == curQueue?.rows?.map { it.id }
            RowsMotion(false, { 1f }, rowsOut, rowsOutRise, rowsIn, rowsInRise, oldCover = if (sameRows) ({ 1f }) else rowsOut)
        }
    }

    // The words of the song shown and of the one leaving keep their places through a change.
    val lyricsViews = remember { LyricsViews() }
    SideEffect {
        lyricsViews.keep(cur.lyrics, prev?.lyrics)
        queueRooms.keep(setOfNotNull(cur.scene.key, prev?.scene?.key))
    }

    Box(modifier.fillMaxSize()) {
        // The old wall stays whole until the new one (its light and its color beyond the screen's
        // edges) has come up over it, so a new wall never shows the old one in a strip at the side.
        val oldWall = prev?.scene?.takeIf { pictureChanges }
        BaseWall((oldWall ?: cur.scene).palette.wall.asColor())
        if (oldWall != null) {
            WallLayer(oldWall, level = level)
            BaseWall(cur.scene.palette.wall.asColor(), alpha = newGlow, beyondOnly = true)
        }
        WallLayer(cur.scene, alpha = newGlow, level = level)
        if (prev != null && pictureChanges) ArtworkLayer(prev.scene, alpha = oldPicture, dim = { dim.picture })
        ArtworkLayer(cur.scene, alpha = newPicture, dim = { dim.picture })
        // A party's code covers the picture and stays while the songs change beneath it; with the
        // wall it changes its paper.
        val newWall = prev != null && prev.scene.appearance != cur.scene.appearance
        if (newWall) PartyCodeLayer(party.on(prev.scene.appearance), prev.scene, alpha = party.shown, dim = { dim.picture })
        PartyCodeLayer(party.on(cur.scene.appearance), cur.scene, alpha = { party.shown() * if (newWall) newPicture() else 1f }, dim = { dim.picture })
        CompositionLocalProvider(LocalLyricsViews provides lyricsViews) {
            if (prevModel != null && prevPlan != null) {
                TextLayer(prevModel, plan = prevPlan, alpha = textOut, rise = textOutRise, dim = { dim.text }, queuePlan = prevQueue, rows = false)
            }
            TextLayer(curModel, plan = curPlan, alpha = textIn, rise = textRise, dim = { dim.text }, queuePlan = curQueue, rows = false)
        }
        if (curQueue != null || prevQueue != null) {
            QueueRowsLayer(curQueue, prevQueue.takeIf { prev != null }, rows, text = { dim.text }, picture = { dim.picture })
        }
        // The status line belongs to the room, not to the track, so it stays; on the other wall
        // its inks cross-fade with the stage.
        if (prevModel != null && kind == Change.WALL) {
            StatusLayer(prevModel, alpha = { 1f - p(0, total, Motion.Sine) })
            StatusLayer(curModel, alpha = { p(0, total, Motion.Sine) })
        } else {
            StatusLayer(curModel)
        }
    }
}

/**
 * How [new] differs from [old], the stage on screen; null when only what the stage doesn't draw
 * has changed (a new link to the same picture), so there is nothing to watch.
 */
internal fun changeOf(old: StageData, new: StageData): Change? {
    if (new.scene === old.scene && new.lyrics === old.lyrics && new.queue == old.queue && drawnAlike(new.track, old.track)) return null
    if (new.track.key != old.track.key) {
        // Only the text changes for the next track of the same cover, unless the background changes with it.
        return if (samePicture(old.scene, new.scene) && new.scene.appearance == old.scene.appearance) Change.TEXT else Change.FULL
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
    Change.ROWS -> Motion.FLOW_SIDES_MS
}

/** The queue's rows glide to their new places in this long (ms), once the text before has left. */
private const val ROWS_GLIDE_MS = 600

/**
 * When the queue changes beneath a song, the rows that go have gone after this long (ms); then the
 * others move until they have settled, and the rows put in come from when their place is nearly clear.
 */
private const val ROWS_CLEAR_MS = 300
private const val ROWS_SETTLED_MS = 750
private const val ROWS_COME_MS = 650

/**
 * Whether the stage draws [a] and [b] alike: the picture comes with the scene, so a new link to
 * it, or what only the quick settings and the queue read, changes nothing on screen.
 */
internal fun drawnAlike(a: TrackInfo, b: TrackInfo): Boolean =
    a == b.copy(artworkUrl = a.artworkUrl, artworkFallbackUrl = a.artworkFallbackUrl, favorite = a.favorite, songPicture = a.songPicture, queueItemId = a.queueItemId)

/** Same picture: consecutive songs of one album. */
internal fun samePicture(a: ArtworkScene, b: ArtworkScene): Boolean =
    a.shape == b.shape && a.coverGenerated == b.coverGenerated && a.thumb.contentEquals(b.thumb)
