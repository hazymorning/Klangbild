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
    /** The smoothed playback position in the track's time (ms), read while drawing, or null while unknown. */
    val positionMs: () -> Long?,
    /** The status line at the top of the text column. */
    val statusLine: StatusLine,
    /** The album's year, shown after its name. */
    val year: Int? = null,
    /** Whether the progress bar shows (the setting, or a key was just pressed). */
    val barShown: Boolean = true,
    /** Whether the progress bar stays (the setting). Without, [barShown] shows it only for a moment after a key. */
    val barStays: Boolean = true,
    /** The slot text, in place of the bar: the station of a radio without a duration, or a notice ("Reconnecting…"). */
    val slotText: String? = null,
    /** The footer's track: what comes next ("Spiegel im Spiegel · Arvo Pärt"), or with lyrics the track itself. */
    val footer: String? = null,
    /** The footer's title alone, for when title and artist don't both fit. */
    val footerTitle: String? = null,
    /** The footer's lead in place of "Up next": "Arrow down" while the hint for the quick settings shows. */
    val footerLead: String? = null,
    /** The chapter of a book or an episode, in the album's place ("The Arrest", "Chapter 7"). */
    val chapter: String? = null,
    /** Where the progress bar starts in the track's time: the chapter's start. */
    val barStartMs: Long = 0L,
    /**
     * The lyrics, while they fill the text column. The [footer] then names the track itself, and
     * the progress bar shows only for a moment after a key ([barShown]).
     */
    val lyrics: Lyrics? = null,
    /** The queue as a list in the text column, while it shows and no lyrics fill the column. The track then stands at its top. */
    val queue: QueueModel? = null,
) {
    /** Whether the track plays or is about to: the progress bar and the lyrics move on. */
    val playing: Boolean get() = status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING
}

/**
 * The status line's parts: the [clock], the custom [values] ("Outside 12 °C") and the [lead]
 * ("Living room · Paused", "Volume 35%"). The lead has its room first, and values that don't fit
 * beside it are dropped from the end.
 */
@Immutable
internal data class StatusLine(val lead: String?, val clock: String?, val values: List<String> = emptyList()) {
    val isEmpty get() = lead == null && clock == null && values.isEmpty()
}

/** The stage's strings for the status line, the slot and the footer. */
internal object StageTexts {
    /**
     * The status line's lead. A volume change shows briefly ("Volume 35%") unless the speaker is
     * muted. Muted or paused, the lead is the [group] and the state ("Living room · Paused"),
     * otherwise the [group], the [pace] ("Pauses at 11:10 PM", "Speed 1.5×") and, with
     * [volumeAlways], the volume.
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
     * Whether the status line always shows the volume: as [always] says, except for this TV's own
     * speaker ([own]) at 100%. There the TV's own volume, which the app can't read, sets what is
     * heard, so "100%" would mislead. Turned down in Music Assistant, its volume shows again.
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

    /** The slot text: the lost connection, or a radio's station. */
    fun slotText(linkDown: Boolean, track: TrackInfo, t: Texts): String? = when {
        linkDown -> t[Strings.stageLinkDown]
        track.durationMs == null -> track.source
        else -> null
    }
}

internal fun Int.asColor() = Color(this)
