package io.github.hazymorning.klangbild.app

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.DisplayOptions
import io.github.hazymorning.klangbild.config.MenuRow
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.QuickRow
import io.github.hazymorning.klangbild.config.SessionOptions
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.data.ha.TrackMatch
import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.UpNext
import io.github.hazymorning.klangbild.domain.UpNextRow
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.text.Texts

/**
 * A scene that is ready to be shown for a track, with the song's [lyrics] while they take the text
 * column, or the [queue] while it shows as a list beside the cover.
 */
data class StageData(val track: TrackInfo, val scene: ArtworkScene, val lyrics: Lyrics? = null, val queue: QueueList? = null)

/** The queue as a list beside the cover: what comes next, and the small covers made so far, by [UpNextRow.id]. */
data class QueueList(val upNext: UpNext, val covers: Map<String, ImageBitmap> = emptyMap()) {
    /**
     * The list once its first row, [track], plays: the rows after it. Null unless that is the
     * song, or when nothing would be left.
     */
    fun movedUp(track: TrackInfo): QueueList? {
        val first = upNext.rows.firstOrNull()?.takeIf { TrackMatch.sameTitle(it.track.title, track.title) } ?: return null
        val rows = upNext.rows.drop(1).ifEmpty { return null }
        return QueueList(UpNext(rows), covers - first.id)
    }
}

/** A card of the cover flow with its scene, ready to be shown. */
data class FlowEntry(val id: Long, val track: TrackInfo, val scene: ArtworkScene) {
    val stage: StageData get() = StageData(track, scene)
}

/**
 * The cover flow as far as it is ready: cards in play order, the one at [current] in the middle,
 * and the middle song's [lyrics] while they take the text column.
 */
data class FlowData(val cards: List<FlowEntry>, val current: Int, val lyrics: Lyrics? = null) {
    val middle: FlowEntry get() = cards[current]

    /** The middle card as the stage shows it: with its words. */
    val stage: StageData get() = StageData(middle.track, middle.scene, lyrics)
}

/** The settings menu while it is open: its lines and the one the cursor is on. */
data class MenuState(
    val rows: List<MenuRow>,
    val cursor: Int,
    /** Up or down is held: the list runs on, and the explanation waits until the key is let go. */
    val held: Boolean = false,
    /** The group whose settings show; null while the groups themselves show. */
    val section: MenuSection? = null,
)

/** The quick menu while it is open: its lines and the one the cursor is on. */
data class QuickState(val rows: List<QuickRow>, val cursor: Int)

/** Everything the UI renders. */
data class UiState(
    /** What shows. While a song that came after the clock waits for its first scene, still the clock ([HeldStage]). */
    val screen: Screen = Screen.Rest(RestReason.CONNECTING),
    /** Nothing has played for the sleep timeout and no menu is open: the screen may turn off, or the app close. */
    val asleep: Boolean = false,
    val sleepAction: SleepAction = SleepAction.RELEASE,
    /**
     * The latest scene that is ready; may lag behind [screen] while a new cover loads. It stays
     * through a switch to Cover Flow until the cards' first scene is made, and goes when another
     * song (or the same one on a new wall, or to or from Cover Flow) comes after the clock or the
     * setup page ([HeldStage]).
     */
    val stage: StageData? = null,
    /** How the queue shows: with Cover Flow its strip ([flow]) shows once it is ready, else the [stage]. */
    val queue: QueueDisplay = QueueDisplay.OFF,
    /**
     * How the queue should show, chosen but not on screen yet, while its stage is being made
     * ([QueueSwitch]); null while no song is on stage. The quick settings' and the menu's Queue
     * value breathe meanwhile.
     */
    val queuePending: QueueDisplay? = null,
    /** The cover flow (with [QueueDisplay.FLOW]); like [stage] it may lag behind [screen], and stays through a switch away. */
    val flow: FlowData? = null,
    /** Light wall by day, dark at night (or as set). */
    val appearance: Appearance = Appearance.DARK,
    /** What the stage shows (clock, progress, album and so on) and how strong its light is. */
    val display: DisplayOptions = DisplayOptions(),
    val source: SourceState = SourceState(LinkState.CONNECTING),
    val session: SessionOptions = SessionOptions(),
    val entityId: String? = null,
    /** Address, code and QR modules while the setup screen is open. */
    val setup: SetupInfo? = null,
    /** The settings menu, null while it is closed. */
    val menu: MenuState? = null,
    /** The quick menu, null while it is closed. */
    val quick: QuickState? = null,
    /** The volume just changed: the status line shows it for a moment. */
    val volumeFlash: Boolean = false,
    /** The chapter of the book or episode on stage, while it has chapters. */
    val chapter: ChapterPlace? = null,
    /** When the sleep timer pauses the music (wall clock), while it is set. */
    val sleepAt: Long? = null,
    /** The words in the language shown; null leaves them to the platform's languages (a screen rendered on its own). */
    val texts: Texts? = null,
    /** The values of one's own in words, as far as Home Assistant can tell them ("Outside 12 °C"). */
    val values: List<String> = emptyList(),
    /** The link of the party playing here, while its code shows where the cover stands (the quick settings' Party code). */
    val partyCode: String? = null,
    /** The quick settings have never been opened: the stage may tell how (arrow down) for a moment. */
    val downHint: Boolean = false,
)
