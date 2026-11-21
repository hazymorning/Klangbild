package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.hazymorning.klangbild.domain.ArtistBio
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueuePlace
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
    /** The line at the column's foot, or null for none. */
    val footer: Footer? = null,
    /** The chapter of a book or an episode, in the album's place ("The Arrest", "Chapter 7"). */
    val chapter: String? = null,
    /** Where the progress bar starts in the track's time: the chapter's start. */
    val barStartMs: Long = 0L,
    /**
     * The lyrics, while they fill the text column. The [footer] then names the track itself, and
     * the progress bar shows only for a moment after a key ([barShown]).
     */
    val lyrics: Lyrics? = null,
    /**
     * A few sentences about the artist, while they fill the text column. The [footer] then names
     * the track itself, and the progress bar shows only for a moment after a key ([barShown]).
     */
    val bio: ArtistBio? = null,
    /**
     * The queue as a list in the text column, while it shows and neither the lyrics nor the
     * artist's page fill the column. The track then stands at its top.
     */
    val queue: QueueModel? = null,
    /** Whether the player still reports this track. Once it reports the next one, its lyrics are over. */
    val current: Boolean = true,
    /** How much later (ms) the lyrics light up than [positionMs] says, read while drawing: a group may play later. */
    val lyricsDelayMs: () -> Long = { 0L },
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

/**
 * The line at the foot of the text column: the track's [place] in its queue ("3 of 45"), then
 * [text] after its [lead]. That is what comes next after "Up next" ("Spiegel im Spiegel · Arvo
 * Pärt"), the hint for the quick settings after the key it names, or beside the song's or the
 * artist's words the track itself, without a lead. When [text] doesn't fit, its [title] alone
 * takes its place, and without any text the place stands alone.
 */
@Immutable
internal data class Footer(val place: String?, val lead: String?, val text: String?, val title: String? = null)

/** The stage's strings for the status line, the slot and the footer. */
internal object StageTexts {
    /**
     * The status line's lead. A volume change shows briefly ("Volume 35%") unless the speaker is
     * muted. Muted or paused, the lead is the [group], the track's [place] in its queue and the
     * state ("Living room · 3 of 45 · Paused"), otherwise the [group], the [place], the [pace]
     * ("Pauses at 11:10 PM", "Speed 1.5×") and, with [volumeAlways], the volume.
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
        place: String? = null,
    ): String? = when {
        volumeFlash && !muted && volume != null -> t.format(Strings.statusVolume, volume)
        muted -> listOfNotNull(group, place, t[Strings.statusMuted]).joinToString(TimeText.SEPARATOR)
        status == PlayerStatus.PAUSED -> listOfNotNull(group, place, t[Strings.statusPaused]).joinToString(TimeText.SEPARATOR)
        else -> listOfNotNull(group, place, pace, volume.takeIf { volumeAlways }).joinToString(TimeText.SEPARATOR).ifEmpty { null }
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

    /**
     * Where [track] stands in its queue, as the footer starts with it: "3 of 45". Only for music
     * in a queue of more than one track: a station's or a book's place says nothing of what plays.
     */
    fun place(place: QueuePlace?, track: TrackInfo, t: Texts): String? =
        place?.takeIf { track.kind == MediaKind.MUSIC && it.count > 1 }
            ?.let { t.format(Strings.stagePlace, t.integer(it.number), t.integer(it.count)) }

    /** The slot text: the lost connection, or a radio's station. */
    fun slotText(linkDown: Boolean, track: TrackInfo, t: Texts): String? = when {
        linkDown -> t[Strings.stageLinkDown]
        track.durationMs == null -> track.source
        else -> null
    }
}

internal fun Int.asColor() = Color(this)
