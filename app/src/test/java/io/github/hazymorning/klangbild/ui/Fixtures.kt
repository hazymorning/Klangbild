package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.FlowEntry
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.art.SyntheticCovers
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.ui.flow.CardMotion
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import io.github.hazymorning.klangbild.ui.stage.StageTheater
import io.github.hazymorning.klangbild.ui.stage.StatusLine
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts

/** Test content: the test catalog plus helpers to turn it into scenes and models. */
object Fixtures {
    val tracks = Catalog.tracks

    /** The words of most shots: German, as the catalog's music and rooms are. */
    val texts: Texts get() = TestTexts.german

    /** A 4K panel with the UI at 4K (xxxhdpi), to check crispness and the true cover scale. */
    val TV_4K = ScreenSpec(3840, 2160, 4f)

    /** Renders use Berlin time on 8 October 2026. */
    val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Berlin")

    fun at(hour: Int, minute: Int, day: Int = 8): Long =
        java.time.ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    fun track(cover: SyntheticCover): TrackInfo = tracks.first { it.artworkUrl == Catalog.SCHEME + cover.name }

    val radio: TrackInfo get() = tracks.first { it.artworkUrl == null }

    /** What comes next in most shots. */
    val spiegel = TrackInfo("Spiegel im Spiegel", listOf("Arvo Pärt"))

    /** An English song to the night drive's cover, for the English shots of the words. */
    val night = TrackInfo("Night drive", listOf("Lumen Drive"), "Neon roads", 2024, 251_000, artworkUrl = Catalog.SCHEME + SyntheticCover.NIGHT_DRIVE.name)

    /** Synced words from LRC. */
    fun lyrics(lrc: String): io.github.hazymorning.klangbild.domain.Lyrics = io.github.hazymorning.klangbild.domain.Lrc.parse(lrc)!!

    fun scene(
        track: TrackInfo,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        appearance: Appearance = Appearance.DARK,
        view: StageView = StageView.SINGLE,
        coverField: Boolean = false,
    ): ArtworkScene {
        val cover = track.artworkUrl?.removePrefix(Catalog.SCHEME)?.let { SyntheticCover.valueOf(it) }
        val bmp = cover?.let { SyntheticCovers.render(it) }
        return SceneBuilder.build(track.key, bmp, track.artSeed, screen, appearance, view = view, coverField = coverField)
    }

    /** Cover flow scenes for [tracks] (the middle one is the current track). */
    fun flowScenes(
        tracks: List<TrackInfo>,
        appearance: Appearance = Appearance.DARK,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        coverField: Boolean = false,
    ) = tracks.map { scene(it, screen, appearance, StageView.FLOW, coverField) }

    /** The cards of [scenes] at rest, the one at [current] in the middle. */
    fun restingCards(scenes: List<ArtworkScene>, current: Int) =
        scenes.mapIndexed { i, sc -> CardMotion(i.toLong(), sc.card!!, (i - current).toFloat(), (i - current).toFloat()) }

    fun model(
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
        val text = TrackText.from(track, t[io.github.hazymorning.klangbild.R.string.artists_others])
        return StageModel(
            scene = scene,
            text = if (album) text else text.copy(album = null),
            status = status,
            durationMs = track.durationMs,
            positionMs = { positionMs },
            year = track.year.takeIf { album },
            progress = progress,
            line = StageTexts.line(linkDown, track, t),
            next = StageTexts.next(next, t),
            nextTitle = StageTexts.nextTitle(next, t),
            statusLine = StatusLine(lead, clock, values),
        )
    }

    /**
     * A book or an episode at [position] (ms in its own time): the chapter in the album's place,
     * the bar over the chapter, the next chapter (or [next]) after it; [pace] in the status line.
     */
    fun spokenModel(
        track: TrackInfo,
        position: Long,
        pace: String?,
        appearance: Appearance = Appearance.DARK,
        view: StageView = StageView.SINGLE,
        next: TrackInfo? = null,
        coverField: Boolean = false,
        group: String? = "Wohnzimmer",
        t: Texts = texts,
    ): StageModel {
        val chapter = io.github.hazymorning.klangbild.domain.ChapterPlace.at(track, position) { n ->
            t.format(io.github.hazymorning.klangbild.R.string.chapter_number, n)
        }
        val lead = StageTexts.lead(PlayerStatus.PLAYING, muted = false, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = t, pace = pace)
        return StageModel(
            scene = scene(track, appearance = appearance, view = view, coverField = coverField),
            text = TrackText.from(track, t[io.github.hazymorning.klangbild.R.string.artists_others]),
            status = PlayerStatus.PLAYING,
            durationMs = chapter?.let { it.endMs - it.startMs } ?: track.durationMs,
            positionMs = { position },
            next = chapter?.next ?: StageTexts.next(next, t),
            nextTitle = if (chapter?.next != null) null else StageTexts.nextTitle(next, t),
            statusLine = StatusLine(lead, "21:47"),
            chapter = chapter?.label,
            rangeStartMs = chapter?.startMs ?: 0L,
        )
    }

    /**
     * The stage with the song's [lyrics] at [position] (ms), as the root builds it: the song itself
     * where "Up next" stands, the progress bar only for a moment after a key ([peek]).
     */
    fun lyricsModel(
        track: TrackInfo,
        lyrics: io.github.hazymorning.klangbild.domain.Lyrics,
        position: () -> Long? = { 84_000L },
        status: PlayerStatus = PlayerStatus.PLAYING,
        appearance: Appearance = Appearance.DARK,
        view: StageView = StageView.SINGLE,
        coverField: Boolean = false,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        scene: ArtworkScene = scene(track, screen, appearance, view, coverField),
        clock: String? = "21:47",
        group: String? = "Wohnzimmer",
        t: Texts = texts,
        peek: Boolean = false,
        linkDown: Boolean = false,
    ): StageModel {
        val lead = StageTexts.lead(status, muted = false, volumeFlash = false, volume = null, group = group, volumeAlways = false, t = t)
        return StageModel(
            scene = scene,
            text = TrackText.from(track, t[io.github.hazymorning.klangbild.R.string.artists_others]),
            status = status,
            durationMs = track.durationMs,
            positionMs = position,
            year = track.year,
            progress = peek,
            line = StageTexts.line(linkDown, track, t),
            next = StageTexts.next(track, t),
            nextTitle = StageTexts.nextTitle(track, t),
            statusLine = StatusLine(lead, clock),
            lyrics = lyrics,
        )
    }

    /** Content rendered as a still: no loops of its own, so the capture can wait for idle. */
    @Composable
    fun Still(content: @Composable () -> Unit) = Still(texts, content)

    /** A still in [t]'s words. */
    @Composable
    fun Still(t: Texts, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalStill provides true, LocalTexts provides t, content = content)
    }

    fun stage(
        track: TrackInfo,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        appearance: Appearance = Appearance.DARK,
        coverField: Boolean = false,
    ) = StageData(track, scene(track, screen, appearance, coverField = coverField))

    /** A cover flow of [scenes] (ids as given), the card with id [middle] in the middle. */
    fun flow(scenes: Map<Long, ArtworkScene>, ids: List<Long>, middle: Long): FlowData {
        val cards = ids.map { id ->
            val sc = scenes.getValue(id)
            FlowEntry(id, tracks.first { it.key == sc.key }, sc)
        }
        return FlowData(cards, cards.indexOfFirst { it.id == middle })
    }

    /** The settings menu opened on [entry]'s group, the cursor on [entry]. */
    fun menu(
        entry: io.github.hazymorning.klangbild.config.MenuEntry,
        settings: io.github.hazymorning.klangbild.config.Settings,
        ctx: io.github.hazymorning.klangbild.config.MenuContext = io.github.hazymorning.klangbild.config.MenuContext(io.github.hazymorning.klangbild.domain.LinkState.CONNECTED),
        t: Texts = texts,
    ): io.github.hazymorning.klangbild.app.MenuState {
        val section = entry.section
        val rows = io.github.hazymorning.klangbild.config.SettingsMenu.rows(settings, ctx, t, section)
        return io.github.hazymorning.klangbild.app.MenuState(rows, io.github.hazymorning.klangbild.config.SettingsMenu.entriesOf(section).indexOf(entry), section = section)
    }

    /** The settings menu on its groups, the cursor on [cursor]. */
    fun menuGroups(cursor: Int = 0, t: Texts = texts) =
        io.github.hazymorning.klangbild.app.MenuState(io.github.hazymorning.klangbild.config.SettingsMenu.groups(t), cursor)

    /** The animated cover flow for one strip; what comes next is the card to the right. */
    @Composable
    fun FlowTheaterOf(flow: FlowData, status: PlayerStatus = PlayerStatus.PLAYING, partyCode: String? = null) {
        val lead = StageTexts.lead(status, false, volumeFlash = false, volume = null, group = "Wohnzimmer + 5", volumeAlways = false, t = texts)
        val next = flow.cards.getOrNull(flow.current + 1)?.track
        io.github.hazymorning.klangbild.ui.flow.FlowTheater(
            flow = flow,
            status = status,
            modelFor = { s -> model(s.track, next = next, status = status, scene = s.scene, lead = lead) },
            partyCode = partyCode,
        )
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
        StageTheater(
            stage = stage,
            status = status,
            modelFor = { s -> model(s.track, next = next, status = status, scene = s.scene, lead = lead) },
            intensity = { intensity },
            dimmed = linkDown,
            partyCode = partyCode,
        )
    }
}
