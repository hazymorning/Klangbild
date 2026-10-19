package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.LandscapeUi
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.landscape.LabelSide
import io.github.hazymorning.klangbild.landscape.Place
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.World
import io.github.hazymorning.klangbild.ui.stage.PlayedLines
import java.time.ZoneId

/**
 * The landscape as a screen of the app. It shows the picture for Home Assistant's place and
 * weather (or a place guessed from the time zone, and a fair sky), with the music's label on the
 * near bank.
 */
@Composable
fun LandscapeScreen(ui: UiState, landscape: LandscapeUi, clock: LandscapeClock, positionOf: (String) -> Long?, now: () -> Long, modifier: Modifier = Modifier) {
    val feed = rememberLandscapeFeed(ui, landscape, clock, now)
    val side = labelSide(landscape.miniPlayer)
    // A label keeps its own cover and time while it leaves, once the stage has let its scene go
    // and the player reports the next song.
    val cover = remember { LabelCover() }
    val played = remember { PlayedLines() }
    val current by rememberUpdatedState(landscape.track?.key)
    Landscape(feed, modifier, label = side) { look ->
        val text = landscape.track?.takeIf { side != LabelSide.NONE }?.let { track ->
            val key = track.key
            LabelText(track, cover.of(track, artworkFor(ui, track)), landscape.status == PlayerStatus.PLAYING) {
                played.position(key, plays = key == current) { positionOf(key) }
            }
        }
        if (text != null) LandscapeLabel(text, side, inks = { look().label }, coverLight = { look().labelCover })
    }
}

/**
 * What the landscape knows of the world: Home Assistant's place (or one guessed from the time
 * zone) and the weather [landscape] shows.
 */
@Composable
fun rememberLandscapeFeed(ui: UiState, landscape: LandscapeUi?, clock: LandscapeClock, now: () -> Long): LandscapeFeed {
    val latest by rememberUpdatedState(ui)
    val shown by rememberUpdatedState(landscape)
    return remember(clock) {
        LandscapeFeed(
            place = { latest.source.place?.let { (lat, lon) -> Place(lat, lon) } ?: World.guessPlace(ZoneId.systemDefault()) },
            weather = { shown?.weather?.at(now()) },
            clock = clock,
            wall = now,
        )
    }
}

/** The label's side for the mini-player setting. */
fun labelSide(miniPlayer: MiniPlayer): LabelSide = when (miniPlayer) {
    MiniPlayer.LEFT -> LabelSide.LEFT
    MiniPlayer.RIGHT -> LabelSide.RIGHT
    MiniPlayer.OFF -> LabelSide.NONE
}

/** The cover the label showed last, kept for its song while the stage no longer has it. */
private class LabelCover {
    private var key: String? = null
    private var scene: ArtworkScene? = null

    fun of(track: TrackInfo, now: ArtworkScene?): ArtworkScene? {
        if (now != null) {
            key = track.key
            scene = now
        }
        return scene.takeIf { key == track.key }
    }
}

/**
 * The scene with [track]'s cover, from the stage or the cover flow, once it is ready. Until a
 * new picture of the same song is ready (a station's song getting its own), the one shown stays.
 */
private fun artworkFor(ui: UiState, track: TrackInfo): ArtworkScene? {
    val picture = track.pictureKey
    return ui.stage?.takeIf { it.track.pictureKey == picture }?.scene
        ?: ui.flow?.cards?.firstOrNull { it.track.pictureKey == picture }?.scene
        ?: ui.stage?.takeIf { it.track.key == track.key }?.scene
        ?: ui.flow?.middle?.takeIf { it.track.key == track.key }?.scene
}
