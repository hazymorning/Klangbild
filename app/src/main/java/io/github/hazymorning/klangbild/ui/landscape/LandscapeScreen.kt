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
import java.time.ZoneId

/**
 * The landscape as a screen of the app. It shows the picture for Home Assistant's place and
 * weather (or a place guessed from the time zone, and a fair sky), with the music's label on the
 * near bank.
 */
@Composable
fun LandscapeScreen(ui: UiState, landscape: LandscapeUi, clock: LandscapeClock, positionNow: () -> Long?, now: () -> Long, modifier: Modifier = Modifier) {
    val feed = rememberLandscapeFeed(ui, landscape, clock, now)
    val side = labelSide(landscape.miniPlayer)
    Landscape(feed, modifier, label = side) { look ->
        val text = landscape.track?.takeIf { side != LabelSide.NONE }?.let { track ->
            LabelText(track, artworkFor(ui, track), landscape.status == PlayerStatus.PLAYING, positionNow)
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
