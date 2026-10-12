package io.github.hazymorning.klangbild.ui.vista

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.VistaUi
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.vista.LabelSide
import io.github.hazymorning.klangbild.vista.Place
import io.github.hazymorning.klangbild.vista.VistaClock
import io.github.hazymorning.klangbild.vista.WeatherMood
import io.github.hazymorning.klangbild.vista.World
import java.time.ZoneId

/**
 * The landscape as a screen of the app. It shows the picture for Home Assistant's place and
 * weather (or a place guessed from the time zone, and a fair sky), with the music's label on the
 * near bank.
 */
@Composable
fun VistaScreen(ui: UiState, vista: VistaUi, clock: VistaClock, positionNow: () -> Long?, now: () -> Long, modifier: Modifier = Modifier) {
    val feed = rememberVistaFeed(ui, clock, now)
    val side = labelSide(vista.miniPlayer)
    Vista(feed, modifier, label = side) { look ->
        val text = vista.track?.takeIf { side != LabelSide.NONE }?.let { track ->
            LabelText.Music(track, artworkFor(ui, track), vista.status == PlayerStatus.PLAYING, positionNow)
        }
        if (text != null) VistaLabel(text, side, inks = { look().label }, coverLight = { look().labelCover })
    }
}

/** What the landscape knows of the world: Home Assistant's place (or one guessed from the time zone) and weather. */
@Composable
fun rememberVistaFeed(ui: UiState, clock: VistaClock, now: () -> Long): VistaFeed {
    val latest by rememberUpdatedState(ui)
    return remember(clock) {
        VistaFeed(
            place = { latest.source.place?.let { (lat, lon) -> Place(lat, lon) } ?: World.guessPlace(ZoneId.systemDefault()) },
            weather = { WeatherMood.of(latest.source.weather, latest.source.cloudCoverage, latest.source.windKmh) },
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
