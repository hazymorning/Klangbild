package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.hazymorning.klangbild.app.AppFixtures
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.QueueList
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.flow.CardMotion
import io.github.hazymorning.klangbild.ui.stage.QueueModel
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import io.github.hazymorning.klangbild.ui.stage.StageTheater
import io.github.hazymorning.klangbild.ui.stage.StatusLine
import io.github.hazymorning.klangbild.ui.theme.AndroidGlyphs
import io.github.hazymorning.klangbild.ui.theme.LocalFaces
import io.github.hazymorning.klangbild.ui.theme.LocalGlyphs
import io.github.hazymorning.klangbild.ui.theme.faces

/**
 * The screens' test content: the controller's ([AppFixtures]), plus the stage's models and the
 * theaters that move them.
 */
object Fixtures : AppFixtures() {
    /** The cards of [scenes] at rest, the one at [current] in the middle. */
    internal fun restingCards(scenes: List<ArtworkScene>, current: Int) =
        scenes.mapIndexed { i, sc -> CardMotion(sc.card!!, (i - current).toFloat(), (i - current).toFloat()) }

    internal fun model(
        track: TrackInfo,
        next: TrackInfo? = null,
        status: PlayerStatus = PlayerStatus.PLAYING,
        positionMs: Long? = track.durationMs?.let { it / 3 },
        screen: ScreenSpec = ScreenSpec.TV_1080,
        clock: String? = "21:47",
        group: String? = null,
        t: Texts = texts,
        lead: String? = StageTexts.lead(status, muted = false, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = t),
        appearance: Appearance = Appearance.DARK,
        scene: ArtworkScene = scene(track, screen, appearance),
        album: Boolean = true,
        progress: Boolean = true,
        linkDown: Boolean = false,
        values: List<String> = emptyList(),
    ): StageModel {
        val text = TrackText.from(track, t[io.github.hazymorning.klangbild.text.Strings.artistsOthers])
        return StageModel(
            scene = scene,
            text = if (album) text else text.copy(album = null),
            status = status,
            durationMs = track.durationMs,
            positionMs = { positionMs },
            year = track.year.takeIf { album },
            barShown = progress,
            slotText = StageTexts.slotText(linkDown, track, t),
            footer = StageTexts.footer(next, t),
            footerTitle = StageTexts.footerTitle(next, t),
            statusLine = StatusLine(lead, clock, values),
        )
    }

    /**
     * A book or an episode at [position] (ms in its own time): the chapter in the album's place,
     * the bar over the chapter, the next chapter (or [next]) after it; [pace] in the status line.
     */
    internal fun spokenModel(
        track: TrackInfo,
        position: Long,
        pace: String?,
        appearance: Appearance = Appearance.DARK,
        flow: Boolean = false,
        next: TrackInfo? = null,
        coverField: Boolean = false,
        group: String? = "Wohnzimmer",
        t: Texts = texts,
    ): StageModel {
        val chapter = io.github.hazymorning.klangbild.domain.ChapterPlace.at(track, position) { n ->
            t.format(io.github.hazymorning.klangbild.text.Strings.chapterNumber, n)
        }
        val lead = StageTexts.lead(PlayerStatus.PLAYING, muted = false, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = t, pace = pace)
        return StageModel(
            scene = scene(track, appearance = appearance, flow = flow, coverField = coverField),
            text = TrackText.from(track, t[io.github.hazymorning.klangbild.text.Strings.artistsOthers]),
            status = PlayerStatus.PLAYING,
            durationMs = chapter?.let { it.endMs - it.startMs } ?: track.durationMs,
            positionMs = { position },
            footer = chapter?.next ?: StageTexts.footer(next, t),
            footerTitle = if (chapter?.next != null) null else StageTexts.footerTitle(next, t),
            statusLine = StatusLine(lead, "21:47"),
            chapter = chapter?.label,
            barStartMs = chapter?.startMs ?: 0L,
        )
    }

    /**
     * The stage with the song's [lyrics] at [position] (ms), as the root builds it: the song itself
     * where "Up next" stands, the progress bar only for a moment after a key ([peek]).
     */
    internal fun lyricsModel(
        track: TrackInfo,
        lyrics: io.github.hazymorning.klangbild.domain.Lyrics,
        position: () -> Long? = { 84_000L },
        status: PlayerStatus = PlayerStatus.PLAYING,
        appearance: Appearance = Appearance.DARK,
        flow: Boolean = false,
        coverField: Boolean = false,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        scene: ArtworkScene = scene(track, screen, appearance, flow, coverField),
        clock: String? = "21:47",
        group: String? = "Wohnzimmer",
        t: Texts = texts,
        peek: Boolean = false,
        linkDown: Boolean = false,
    ): StageModel {
        val lead = StageTexts.lead(status, muted = false, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = t)
        return StageModel(
            scene = scene,
            text = TrackText.from(track, t[io.github.hazymorning.klangbild.text.Strings.artistsOthers]),
            status = status,
            durationMs = track.durationMs,
            positionMs = position,
            year = track.year,
            barShown = peek,
            slotText = StageTexts.slotText(linkDown, track, t),
            footer = StageTexts.footer(track, t),
            footerTitle = StageTexts.footerTitle(track, t),
            statusLine = StatusLine(lead, clock),
            lyrics = lyrics,
        )
    }

    /** The stage with the queue as a list ([list]), as the root builds it: the song at the top, the rows under it. */
    internal fun queueModel(
        track: TrackInfo,
        list: QueueList,
        status: PlayerStatus = PlayerStatus.PLAYING,
        appearance: Appearance = Appearance.DARK,
        coverField: Boolean = false,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        scene: ArtworkScene = scene(track, screen, appearance, coverField = coverField),
        clock: String? = "21:47",
        group: String? = "Wohnzimmer",
        t: Texts = texts,
        progress: Boolean = true,
        barStays: Boolean = progress,
        positionMs: Long? = track.durationMs?.let { it / 3 },
        album: Boolean = true,
    ): StageModel {
        val lead = StageTexts.lead(status, muted = false, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = t)
        val text = TrackText.from(track, t[io.github.hazymorning.klangbild.text.Strings.artistsOthers])
        return StageModel(
            scene = scene,
            text = if (album) text else text.copy(album = null),
            status = status,
            durationMs = track.durationMs,
            positionMs = { positionMs },
            year = track.year.takeIf { album },
            barShown = progress,
            barStays = barStays,
            slotText = StageTexts.slotText(false, track, t),
            statusLine = StatusLine(lead, clock),
            queue = QueueModel.of(list, t),
        )
    }

    /** Content rendered as a still: no loops of its own, so the capture can wait for idle. */
    @Composable
    fun Still(content: @Composable () -> Unit) = Still(texts, content)

    /** A still in [t]'s words. */
    @Composable
    fun Still(t: Texts, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalStill provides true, LocalTexts provides t, LocalFaces provides faces(), LocalGlyphs provides AndroidGlyphs, content = content)
    }

    /** [content] with the platform's glyphs, which the root takes from its hooks: the rest clock needs them. */
    @Composable
    fun Glyphs(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalGlyphs provides AndroidGlyphs, content = content)
    }

    /** The animated cover flow for one strip; what comes next is the card to the right. */
    @Composable
    fun FlowTheaterOf(flow: FlowData, status: PlayerStatus = PlayerStatus.PLAYING, partyCode: String? = null) {
        val lead = StageTexts.lead(status, false, volumeFlash = false, volume = null, group = "Wohnzimmer + 5", volumeAlways = false, t = texts)
        val next = flow.cards.getOrNull(flow.current + 1)?.track
        CompositionLocalProvider(LocalTexts provides texts) {
            io.github.hazymorning.klangbild.ui.flow.FlowTheater(
                flow = flow,
                status = status,
                modelFor = { s -> model(s.track, next = next, status = status, scene = s.scene, lead = lead) },
                partyCode = partyCode,
            )
        }
    }

    /**
     * The animated stage with the queue's list where [StageData.queue] has one, in [t]'s words, the
     * speaker [group] in the status line; the bar shown as [progress] says, and staying as
     * [barStays] says (else only for a moment).
     */
    @Composable
    fun QueueTheater(
        stage: StageData,
        status: PlayerStatus = PlayerStatus.PLAYING,
        t: Texts = texts,
        group: String = "Wohnzimmer",
        next: TrackInfo? = null,
        progress: Boolean = true,
        barStays: Boolean = progress,
    ) {
        CompositionLocalProvider(LocalTexts provides t) {
            StageTheater(
                stage = stage,
                status = status,
                modelFor = { s ->
                    val list = s.queue
                    if (list != null) queueModel(s.track, list, status = status, scene = s.scene, group = group, t = t, progress = progress, barStays = barStays)
                    else model(s.track, next = next, status = status, scene = s.scene, t = t, group = group)
                },
            )
        }
    }

    /** The animated stage (pause dimming, buffering pulse) for one stage, frozen in time. */
    @Composable
    fun Theater(
        stage: StageData,
        status: PlayerStatus,
        next: TrackInfo? = null,
        intensity: Float = 1f,
        group: String? = null,
        muted: Boolean = false,
        linkDown: Boolean = false,
        partyCode: String? = null,
    ) {
        val lead = StageTexts.lead(status, muted, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = texts)
        CompositionLocalProvider(LocalTexts provides texts) {
            StageTheater(
                stage = stage,
                status = status,
                modelFor = { s -> model(s.track, next = next, status = status, scene = s.scene, lead = lead) },
                intensity = { intensity },
                linkDown = linkDown,
                partyCode = partyCode,
            )
        }
    }
}
