package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.art.SyntheticCovers
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.UpNext
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.landscape.WeatherGlide
import io.github.hazymorning.klangbild.landscape.WeatherMood
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.text.testZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Test content up to the controller: the test catalog's tracks, the scenes made of their covers,
 * and the stages, flows, menus and landscapes the controller hands the screens. The controller's
 * tests and the screens' each have a `Fixtures` built on it; the screens' adds their models and
 * theaters.
 */
abstract class AppFixtures {
    val tracks = Catalog.tracks

    /** The words of most shots: German, as the catalog's music and rooms are. */
    val texts: Texts get() = TestTexts.german

    /** A 4K panel with the UI at 4K (xxxhdpi), to check crispness and the true cover scale. */
    val TV_4K = ScreenSpec(3840, 2160, 4f)

    /** Renders use Berlin time on 8 October 2026. */
    val zone: TimeZone = testZone("Europe/Berlin")

    fun at(hour: Int, minute: Int, day: Int = 8): Long =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(zone).toEpochMilliseconds()

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
        flow: Boolean = false,
        coverField: Boolean = false,
    ): ArtworkScene {
        val cover = track.artworkUrl?.removePrefix(Catalog.SCHEME)?.let { SyntheticCover.valueOf(it) }
        val bmp = cover?.let { SyntheticCovers.render(it) }
        return SceneBuilder.build(track.key, bmp, track.artSeed, screen, appearance, flow = flow, coverField = coverField)
    }

    /** Cover flow scenes for [tracks] (the middle one is the current track). */
    fun flowScenes(
        tracks: List<TrackInfo>,
        appearance: Appearance = Appearance.DARK,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        coverField: Boolean = false,
    ) = tracks.map { scene(it, screen, appearance, flow = true, coverField = coverField) }

    /** The queue's list: what [coming] holds, the small covers made at once ([covers]: none yet). */
    fun queueList(
        coming: List<TrackInfo>,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        covers: Boolean = true,
    ): QueueList {
        val upNext = UpNext.of(coming)!!
        val px = kotlin.math.round(StageGrid(screen).rowCover * screen.density).toInt()
        val made = if (!covers) emptyMap() else upNext.rows.associate { row ->
            val cover = row.track.artworkUrl?.removePrefix(Catalog.SCHEME)?.let { name -> SyntheticCover.entries.firstOrNull { it.name == name } }
            row.id to SceneBuilder.rowCover(cover?.let { SyntheticCovers.render(it) }, row.track.artSeed, px)
        }
        return QueueList(upNext, made)
    }

    fun stage(
        track: TrackInfo,
        screen: ScreenSpec = ScreenSpec.TV_1080,
        appearance: Appearance = Appearance.DARK,
        coverField: Boolean = false,
    ) = StageData(track, scene(track, screen, appearance, coverField = coverField))

    /** The landscape with [track]'s label on [side], showing the weather Home Assistant calls [weather] (none: a fair sky). */
    fun landscape(track: TrackInfo?, status: PlayerStatus?, side: MiniPlayer, weather: String? = null) =
        LandscapeUi(track, status, side, WeatherGlide.still(WeatherMood.of(weather, null) ?: Weather.FAIR))

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
    ): MenuState {
        val section = entry.section
        val rows = io.github.hazymorning.klangbild.config.SettingsMenu.rows(settings, ctx, t, section)
        return MenuState(rows, io.github.hazymorning.klangbild.config.SettingsMenu.entriesOf(section).indexOf(entry), section = section)
    }

    /** The settings menu on its groups, the cursor on [cursor]. */
    fun menuGroups(cursor: Int = 0, t: Texts = texts) =
        MenuState(io.github.hazymorning.klangbild.config.SettingsMenu.groups(t), cursor)
}
