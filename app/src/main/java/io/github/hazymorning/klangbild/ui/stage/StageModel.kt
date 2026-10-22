package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.text.Texts
import kotlin.math.roundToInt

/**
 * Everything the stage draws for one track. Time-dependent values are providers so that
 * ticking them never recomposes the stage.
 */
@Immutable
class StageModel(
    val scene: ArtworkScene,
    val text: TrackText,
    val status: PlayerStatus,
    /** The progress bar's length: the track's, or the chapter's in a book with chapters. */
    val durationMs: Long?,
    /** Smoothed playback position "now" in the item's own time (null without duration information). */
    val positionMs: () -> Long?,
    /** Year of the album, shown after its name. */
    val year: Int? = null,
    /** The progress bar is shown (setting, or a key was just pressed). */
    val progress: Boolean = true,
    /**
     * A line in the progress bar's place: the station of a radio without duration, or a
     * notice ("Reconnecting…"). Wins over the bar.
     */
    val line: String? = null,
    /** What comes next, as in "Spiegel im Spiegel · Arvo Pärt". */
    val next: String? = null,
    /** The next item's title alone, for when title and artist don't both fit. */
    val nextTitle: String? = null,
    /** The words before [next]: "Up next", unless the line tells how to open the quick settings. */
    val nextLead: String? = null,
    /** The status line at the top right: where the music plays, the volume, the time. */
    val statusLine: StatusLine? = null,
    /** The chapter of a book or an episode, in the album's place ("The Arrest", "Chapter 7"). */
    val chapter: String? = null,
    /** Where the progress bar starts in the item's time: the chapter's start. */
    val rangeStartMs: Long = 0L,
    /**
     * The song's words, while they take the text column: then [next] names the song itself,
     * and the progress bar shows only for a moment after a key ([progress]).
     */
    val lyrics: Lyrics? = null,
    /**
     * The queue as a list beside the cover, while it shows (and no words take the column): then
     * the song stands at the top of the column and [next] says how much follows ("11 tracks · 48 min").
     */
    val queue: QueueModel? = null,
)

/**
 * The quiet line at the top of the column: [clock], the time, then the [values] of one's own
 * ("Outside 12 °C"), then [lead]: where the music plays, a pause or a passing volume
 * ("Everywhere", "Paused", "Volume 35%"). The lead always has its room; values that do not fit
 * beside it are left out, from the last.
 */
@Immutable
data class StatusLine(val lead: String?, val clock: String?, val values: List<String> = emptyList()) {
    val isEmpty get() = lead == null && clock == null && values.isEmpty()
}

/** The stage's words for states and what comes next. */
object StageTexts {
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
        volumeFlash && !muted && volume != null -> t.format(R.string.status_volume, volume)
        muted -> listOfNotNull(group, t[R.string.status_muted]).joinToString(TimeText.SEPARATOR)
        status == PlayerStatus.PAUSED -> listOfNotNull(group, t[R.string.status_paused]).joinToString(TimeText.SEPARATOR)
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
    fun sleep(atMs: Long, t: Texts, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        t.format(R.string.status_pause_at, t.clock(atMs, zone))

    /** "Spiegel im Spiegel · Arvo Pärt". */
    fun next(track: TrackInfo?, t: Texts): String? =
        track?.let { TrackText.from(it, t[R.string.artists_others]) }?.let { listOfNotNull(it.title, it.shortArtists).joinToString(TimeText.SEPARATOR) }

    /** "11 tracks · 48 min": how many tracks follow and how long they play, as far as known; null when neither is. */
    fun queue(count: Int?, durationMs: Long?, t: Texts): String? =
        listOfNotNull(count?.let { t.plural(R.plurals.queue_tracks, it, t.number(it.toDouble(), 0)) }, durationMs?.let { TimeText.length(it, t) })
            .joinToString(TimeText.SEPARATOR).ifEmpty { null }

    /** "Spiegel im Spiegel". */
    fun nextTitle(track: TrackInfo?, t: Texts): String? = track?.let { TrackText.from(it, t[R.string.artists_others]).title }

    /** A line in the progress bar's place: the lost connection, or a radio's station. */
    fun line(linkDown: Boolean, track: TrackInfo, t: Texts): String? = when {
        linkDown -> t[R.string.stage_link_down]
        track.durationMs == null -> track.source
        else -> null
    }
}

fun Int.asColor() = Color(this)
