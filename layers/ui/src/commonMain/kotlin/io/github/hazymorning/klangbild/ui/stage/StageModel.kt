package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

/**
 * Everything the stage draws for one track. Time-dependent values are providers so that
 * ticking them never recomposes the stage.
 */
@Immutable
internal class StageModel(
    val scene: ArtworkScene,
    val text: TrackText,
    val status: PlayerStatus,
    /** The progress bar's length: the track's, or the chapter's in a book with chapters. */
    val durationMs: Long?,
    /** Smoothed playback position "now" in the item's own time (null without duration information). */
    val positionMs: () -> Long?,
    /** The status line at the top right: where the music plays, the volume, the time. */
    val statusLine: StatusLine,
    /** Year of the album, shown after its name. */
    val year: Int? = null,
    /** The progress bar is shown (setting, or a key was just pressed). */
    val barShown: Boolean = true,
    /** The progress bar stays (the setting); else [barShown] shows it only for a moment after a key. */
    val barStays: Boolean = true,
    /**
     * The words in the slot under the text block: the station of a radio without duration, or a
     * notice ("Reconnecting…"). They win over the bar.
     */
    val slotText: String? = null,
    /** The footer, what comes next: "Spiegel im Spiegel · Arvo Pärt". */
    val footer: String? = null,
    /** The footer's title alone, for when title and artist don't both fit. */
    val footerTitle: String? = null,
    /** The words before the [footer]: "Up next", unless the footer tells how to open the quick settings. */
    val footerLead: String? = null,
    /** The chapter of a book or an episode, in the album's place ("The Arrest", "Chapter 7"). */
    val chapter: String? = null,
    /** Where the progress bar starts in the item's time: the chapter's start. */
    val barStartMs: Long = 0L,
    /**
     * The song's words, while they take the text column: then the [footer] names the song itself,
     * and the progress bar shows only for a moment after a key ([barShown]).
     */
    val lyrics: Lyrics? = null,
    /** The queue as a list beside the cover, while it shows (and no words take the column): then the song stands at the top of the column. */
    val queue: QueueModel? = null,
) {
    /** The track plays, or is about to: the progress bar and the lyrics move on. */
    val playing: Boolean get() = status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING
}

/**
 * The quiet line at the top of the column: [clock], the time, then the [values] of one's own
 * ("Outside 12 °C"), then [lead]: where the music plays, a pause or a passing volume
 * ("Everywhere", "Paused", "Volume 35%"). The lead always has its room; values that do not fit
 * beside it are left out, from the last.
 */
@Immutable
internal data class StatusLine(val lead: String?, val clock: String?, val values: List<String> = emptyList()) {
    val isEmpty get() = lead == null && clock == null && values.isEmpty()
}

/** The stage's words for states and what comes next. */
internal object StageTexts {
    /**
     * The status line's lead: a passing volume change wins, then mute and pause (after the
     * place: "Living room · Paused"), otherwise the place, how it plays ([pace]: "Pauses at
     * 11:10 PM", "Speed 1.5×") and the volume, if it is always shown.
     */
    fun lead(
        status: PlayerStatus,
        muted: Boolean,
        volumeFlash: Boolean,
        volume: String?,
        group: String?,
        volumeAlways: Boolean,
        t: Texts,
        pace: String? = null,
    ): String? = when {
        volumeFlash && !muted && volume != null -> t.format(Strings.statusVolume, volume)
        muted -> listOfNotNull(group, t[Strings.statusMuted]).joinToString(TimeText.SEPARATOR)
        status == PlayerStatus.PAUSED -> listOfNotNull(group, t[Strings.statusPaused]).joinToString(TimeText.SEPARATOR)
        else -> listOfNotNull(group, pace, volume.takeIf { volumeAlways }).joinToString(TimeText.SEPARATOR).ifEmpty { null }
    }

    /**
     * Whether the volume is always shown: as [always] says, except for this TV's own speaker
     * ([own]) at full volume, which is how it stays while the TV's own volume, which the app
     * cannot see, sets what is heard; "100%" would say nothing true there. Turned down in Music
     * Assistant, its volume shows again.
     */
    fun volumeAlways(always: Boolean, own: Boolean, level: Float?): Boolean =
        always && !(own && level != null && (level * 100f).roundToInt() >= 100)

    /** "Pauses at 11:10 PM": when the sleep timer pauses the music. */
    fun sleep(atMs: Long, t: Texts, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        t.format(Strings.statusPauseAt, t.clock(atMs, zone))

    /** [track] as the footer names it: "Spiegel im Spiegel · Arvo Pärt". */
    fun footer(track: TrackInfo?, t: Texts): String? =
        track?.let { TrackText.from(it, t[Strings.artistsOthers]) }?.let { listOfNotNull(it.title, it.shortArtists).joinToString(TimeText.SEPARATOR) }

    /** [track]'s title alone in the footer: "Spiegel im Spiegel". */
    fun footerTitle(track: TrackInfo?, t: Texts): String? = track?.let { TrackText.from(it, t[Strings.artistsOthers]).title }

    /** The words in the progress bar's place: the lost connection, or a radio's station. */
    fun slotText(linkDown: Boolean, track: TrackInfo, t: Texts): String? = when {
        linkDown -> t[Strings.stageLinkDown]
        track.durationMs == null -> track.source
        else -> null
    }
}

internal fun Int.asColor() = Color(this)
