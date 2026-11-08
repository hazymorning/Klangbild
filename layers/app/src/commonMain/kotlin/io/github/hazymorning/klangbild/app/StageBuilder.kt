package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.domain.CoverStrip
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SongLyrics
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.Strip
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.UpNext
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * Builds the stage for the track on it as far as its scenes are ready: the single cover with the
 * lyrics or the queue list in the text column, or Cover Flow's strip. Scenes and row covers are
 * made off the main thread, and [changed] runs as each one is ready. Called under the
 * controller's lock.
 */
internal class StageBuilder(
    scope: CoroutineScope,
    private val artwork: ArtworkLoader,
    log: Log,
    now: () -> Long,
    private val changed: () -> Unit,
) {
    private val scenes = Scenes(scope, artwork, log, now)
    private val thumbs = Thumbs(scope, artwork, log, now)
    private val strip = CoverStrip()

    /** What was last known of the words of the song on stage: it stays while a moment between two songs says nothing. */
    private var lastLyrics: SongLyrics? = null

    /** Builds for a screen of [spec] from now on. */
    fun screen(spec: ScreenSpec) {
        scenes.show(scenes.frame.copy(screen = spec))
        thumbs.px = (StageGrid(spec).rowCover * spec.density).roundToInt()
    }

    /** Cover Flow's strip as [follow] last left it. */
    private var cards: Strip? = null

    /** Cover Flow shows, else the single cover ([follow]). */
    val flowing: Boolean get() = scenes.frame.flow

    /** Another speaker (or another server) has its own history. */
    fun newSource() {
        strip.clear()
        lastLyrics = null
    }

    /** Lets go of decoded covers and scenes. */
    fun clear() {
        scenes.clear()
        thumbs.clear()
        artwork.clear()
        strip.clear()
    }

    /**
     * Follows [stage]'s track with Cover Flow's strip, in every view, so the strip knows what
     * played when Cover Flow comes, and says whether Cover Flow shows, as [build] then builds it.
     * It shows when chosen ([s]) and while there is another cover beside the song's: one album, a
     * station, a book or an episode on its own has nothing to flow, and the single cover shows
     * instead, as the list shows only with something to come. Cover Flow needs scenes of its own:
     * what showed before stays on screen until the other view's first scene is made, so a switch
     * never waits in the dark, and the song on stage ([onStage]) keeps its scene for a way
     * straight back.
     */
    fun follow(s: Settings, src: SourceState, stage: Screen.Stage, onStage: TrackInfo?): Boolean {
        // Music Assistant's whole queue if it is set up, else Home Assistant's next track.
        val coming = if (src.queueKnown) src.coming.ifEmpty { listOfNotNull(stage.next) } else null
        val strip = strip.update(stage.track, coming, played = src.played).also { cards = it }
        val flow = s.queue == QueueDisplay.FLOW && strip.cards.size > 1
        if (flow != scenes.frame.flow) scenes.show(scenes.frame.copy(flow = flow), keep = onStage?.sceneKey)
        return flow
    }

    /**
     * [ui] with the stage for [stage]'s track: the single cover with the lyrics or the queue
     * list, or Cover Flow, as [follow] said for it. [shown] is the stage published before.
     */
    fun build(ui: UiState, s: Settings, stage: Screen.Stage, look: Look, shown: StageData?): UiState {
        val lyrics = lyricsFor(s, ui.source, stage.track)
        val strip = cards?.takeIf { flowing && it.middle.track == stage.track }
        return if (strip != null) {
            showFlow(ui, stage, strip, look, lyrics)
        } else {
            showStage(ui, stage, look, lyrics, queueFor(s, ui.source, stage.track, lyrics, shown))
        }
    }

    /** The lyrics for [track] in the text column. With Lyrics on, Music Assistant's synced lyrics for a song with a known duration. */
    private fun lyricsFor(s: Settings, src: SourceState, track: TrackInfo): Lyrics? {
        src.lyrics?.let { lastLyrics = it }
        if (!s.lyrics || track.kind != MediaKind.MUSIC || track.durationMs == null) return null
        return lastLyrics?.takeIf { it.key == track.key }?.lyrics
    }

    /**
     * The queue as a list beside the cover for [track], with Queue on List: what Music Assistant's
     * queue says comes after it, while the song plays and no words take the column. A song only
     * (a station or spoken word keeps its own last line), and only with something to come. The
     * small covers its rows have so far come with it. A song the player no longer reports keeps
     * the list it had ([shown]), and so does a song whose queue is being read again. A song the
     * list announced plays a moment before its own queue is read: meanwhile the list before
     * moves up by one, so the rows that stay glide up with the song and nothing comes twice.
     */
    private fun queueFor(s: Settings, src: SourceState, track: TrackInfo, lyrics: Lyrics?, shown: StageData?): QueueList? {
        if (s.queue != QueueDisplay.LIST || lyrics != null || track.kind != MediaKind.MUSIC) return null
        if (src.player?.track?.key != track.key) return shown?.takeIf { it.track.key == track.key }?.queue
        if (!src.queueKnown) {
            val before = shown?.queue ?: return null
            return if (shown.track.key == track.key) before else before.movedUp(track)
        }
        val upNext = UpNext.of(src.coming) ?: return null
        return QueueList(upNext, upNext.rows.mapNotNull { row -> thumbs.ready(row.track)?.let { row.id to it } }.toMap())
    }

    /**
     * [ui] with the stage showing [stage]'s track as soon as its scene is ready (the next track's
     * is made ahead), with the queue's [list] while it shows. The small covers of its rows are
     * asked for after the song's own picture and made one at a time, so they never hold it up: a
     * row whose small cover is still on its way shows without it, and it comes in where it stands.
     */
    private fun showStage(ui: UiState, stage: Screen.Stage, look: Look, lyrics: Lyrics?, list: QueueList?): UiState {
        scenes.retain(setOfNotNull(stage.track.sceneKey, stage.next?.sceneKey))
        val rows = list?.upNext?.rows.orEmpty().map { it.track }
        thumbs.retain(rows)
        // Asked every time: a generated cover standing in for the song's picture may try again.
        scenes.prepare(stage.track, look) { changed() }
        val scene = scenes.ready(stage.track, look)
        if (scene != null) stage.next?.let { scenes.prepare(it, look) }
        for (track in rows) thumbs.prepare(track) { changed() }
        if (scene == null) return ui
        val shown = ui.stage
        if (shown?.track == stage.track && shown.scene === scene && shown.lyrics === lyrics && shown.queue == list && ui.flow == null) return ui
        // Cover Flow, if it showed before, goes now.
        return ui.copy(stage = StageData(stage.track, scene, lyrics, list), flow = null)
    }

    /**
     * Cover Flow: [ui] with the strip [s] around [stage]'s track. Every card's scene is made (the
     * middle one first, then what comes, then what was), and the strip is shown once the middle
     * one is ready; cards still on their way join it as they come. A card whose scene is being
     * lit again keeps the one it has meanwhile. When the wall changes (light to dark), the whole
     * strip changes at once, as soon as every card it shows is ready.
     */
    private fun showFlow(ui: UiState, stage: Screen.Stage, s: Strip, look: Look, lyrics: Lyrics?): UiState {
        scenes.retain(s.cards.map { it.track.sceneKey }.toSet())
        val order = listOf(s.current) + (s.current + 1 until s.cards.size) + (s.current - 1 downTo 0)
        for (i in order) scenes.prepare(s.cards[i].track, look) { changed() }
        val middle = scenes.ready(s.middle.track, look) ?: return ui
        val shown = ui.flow?.cards.orEmpty().associateBy { it.id }
        val newWall = shown.values.any { it.scene.appearance != look.appearance }
        if (newWall && s.cards.any { it.id in shown && scenes.ready(it.track, look) == null && scenes.pending(it.track) }) return ui
        val entries = s.cards.mapNotNull { c ->
            val scene = scenes.ready(c.track, look) ?: shown[c.id]?.scene?.takeIf { !newWall && it.layout == middle.layout }
            scene?.let { FlowEntry(c.id, c.track, it) }
        }
        val flow = FlowData(entries, entries.indexOfFirst { it.id == s.middle.id }, lyrics)
        if (flow == ui.flow && ui.stage == null) return ui
        // The single cover, if it showed before, goes now.
        return ui.copy(flow = flow, stage = null)
    }
}
