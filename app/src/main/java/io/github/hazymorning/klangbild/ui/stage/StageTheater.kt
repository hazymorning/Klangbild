package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.domain.PlayerStatus
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

    /** Same track, new scene (a better cover): a quiet cross-fade. */
    SOFT,

    /** Same track on the other wall (light ↔ dark): the same cross-fade, slower, like the light outside. */
    WALL,

    /** Same track and picture, other words in the column (its lyrics come or go): only the text changes. */
    LYRICS,
    ;

    /** A cross-fade of the same track. */
    val isSoft get() = this == SOFT || this == WALL
}

/**
 * The stage with its transitions. On a track change the new artwork comes up over the old one
 * in a plain cross-fade (the screen never goes dark), while the light on the wall turns from
 * the old colors to the new. The old text leaves first; the new lines rise into place one
 * after another, and so they do when the song's words come or go. A pause dims light, artwork
 * and text; nothing moves.
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
            if (target.scene === old.scene && target.track == old.track && target.lyrics === old.lyrics) return@collect
            val kind = when {
                song == old.track.key && target.scene.appearance != old.scene.appearance -> Change.WALL
                song == old.track.key && target.scene !== old.scene -> Change.SOFT
                song == old.track.key && target.lyrics !== old.lyrics -> Change.LYRICS
                song == old.track.key -> Change.SOFT
                samePicture(old.scene, target.scene) -> Change.TEXT
                else -> Change.FULL
            }
            t.snapTo(0f)
            when (kind) {
                Change.FULL -> text.begin(TextRise(Motion.TEXT_IN_MS, durationOf(kind)))
                Change.TEXT, Change.LYRICS -> text.begin(TextRise(Motion.TEXT_IN_SAME_PICTURE_MS, durationOf(kind)))
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
    fun p(startMs: Int, endMs: Int, easing: androidx.compose.animation.core.Easing = Motion.Dimmer) =
        Motion.phaseMs(t.value, startMs, endMs, total, easing)

    val curModel = remember(cur, modelFor) { modelFor(cur) }
    val prevModel = remember(prev, modelFor) { prev?.let(modelFor) }
    val px = cur.scene.cover.width
    val party = rememberPartyCode(partyCode, setOfNotNull(cur.scene.appearance, prev?.scene?.appearance), px) { url, wall -> partyCard(url, px, wall) }
    val curPlan = rememberTextPlan(curModel)
    val prevPlan = prevModel?.let { rememberTextPlan(it) }

    val level: () -> Float = { dim.light * intensity() }
    val pictureChanges = prev != null && kind != Change.TEXT && kind != Change.LYRICS
    // A new picture comes up over the old one; the old goes once it is covered (a picture of
    // another shape, such as a round plate, lets the old one's corners fade with it).
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
    // another; a new look of the same song cross-fades in place.
    val textIn: (TextLine) -> Float = { line -> text.level(line) }
    val textRise: (TextLine) -> Float = { line -> if (text.rising) 10f * (1f - text.level(line)) else 0f }
    val stood: (TextLine) -> Float = { line -> if (kind.isSoft) text.level(line) else text.leaving(line) }

    // The words of the song shown and of the one leaving keep their places through a change.
    val lyricsViews = remember { LyricsViews() }
    SideEffect { lyricsViews.keep(cur.lyrics, prev?.lyrics) }

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
                TextLayer(
                    prevModel,
                    plan = prevPlan,
                    alpha = { line -> stood(line) * (if (kind.isSoft) 1f - p(0, total) else 1f - p(0, Motion.TEXT_OUT_MS)) },
                    rise = { line -> 10f * (1f - stood(line)) - (if (kind.isSoft) 0f else 6f * p(0, Motion.TEXT_OUT_MS)) },
                    dim = { dim.text },
                )
            }
            TextLayer(curModel, plan = curPlan, alpha = textIn, rise = textRise, dim = { dim.text })
        }
        // The status line belongs to the room, not to the track, so it stays.
        StatusLayer(curModel)
    }
}

private fun durationOf(change: Change) = when (change) {
    Change.FULL -> Motion.TRACK_CHANGE_MS
    Change.TEXT, Change.LYRICS -> Motion.TEXT_CHANGE_MS
    Change.SOFT -> Motion.SOFT_SWAP_MS
    Change.WALL -> Motion.WALL_CHANGE_MS
}

/** Same picture: consecutive songs of one album. */
internal fun samePicture(a: ArtworkScene, b: ArtworkScene): Boolean =
    a.shape == b.shape && a.coverGenerated == b.coverGenerated && a.thumb.contentEquals(b.thumb)
