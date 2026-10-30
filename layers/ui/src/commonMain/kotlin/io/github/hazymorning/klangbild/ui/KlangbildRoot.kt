package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MotionLevel
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.VolumeDisplay
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.domain.SpeakerLabel
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.flow.FlowTheater
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.PlayedLines
import io.github.hazymorning.klangbild.ui.stage.QueueModel
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import io.github.hazymorning.klangbild.ui.stage.StageTheater
import io.github.hazymorning.klangbild.ui.stage.StatusLine
import io.github.hazymorning.klangbild.ui.theme.Glyphs
import io.github.hazymorning.klangbild.ui.theme.LocalFaces
import io.github.hazymorning.klangbild.ui.theme.LocalGlyphs
import io.github.hazymorning.klangbild.ui.theme.faces
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** The time as [now] tells it, read every [CLOCK_TICK_MS]. */
@Composable
private fun rememberWallClock(now: () -> Long): State<Long> =
    produceState(initialValue = now()) {
        while (true) {
            value = now()
            delay(CLOCK_TICK_MS)
        }
    }

/** What the root needs from the outside world. */
class RootHooks(
    /** The position of the song with this key, or null once the player reports another. */
    val positionOf: (String) -> Long?,
    /** Local time until which hidden parts (time, place, progress) show again after a key press. */
    val peekUntil: State<Long> = mutableLongStateOf(0L),
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** The color field's time (seconds): it runs while music plays, at the motion setting's speed. */
    val fieldTime: () -> Double = { 0.0 },
    /** The platform's own text engine, for the outlines of the rest clock's figures. */
    val glyphs: Glyphs,
)

/**
 * The whole screen. Switches between stage, rest and setup with a slow fade through the
 * background (black, or the light background by day), fades in from black on start (no white
 * flash, ever) and lets everything drift a few pixels for OLED care.
 */
@Composable
internal fun KlangbildRoot(ui: UiState, screen: ScreenSpec, hooks: RootHooks, modifier: Modifier = Modifier) {
    val wall = rememberWallClock(hooks.now)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(ENTRANCE_MS, easing = Motion.Dimmer)) }

    // The first seconds after a start stay dark instead of flashing "Connecting":
    // usually the music is there before anyone could read that line.
    val quiet by produceState(initialValue = true) {
        delay(QUIET_START_MS)
        value = false
    }
    val content = contentOf(ui, quiet)
    CompositionLocalProvider(LocalTexts provides (ui.texts ?: texts()), LocalFaces provides faces(), LocalGlyphs provides hooks.glyphs) {
        Root(ui, screen, hooks, wall, entrance, content, modifier)
    }
}

@Composable
private fun Root(ui: UiState, screen: ScreenSpec, hooks: RootHooks, wall: State<Long>, entrance: Animatable<Float, *>, content: Content, modifier: Modifier) {
    val density = LocalDensity.current.density
    val t = texts()
    // Between two screens the background itself shows, so on the light background a switch
    // never passes through black. On start it comes up from black with everything else.
    val light = ui.appearance == Appearance.LIGHT
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(if (light) lerp(Color.Black, OverlayColors.LIGHT, entrance.value) else Color.Black) }
            .graphicsLayer {
                alpha = entrance.value
                val (dx, dy) = Motion.drift(wall.value, DRIFT_DP * density)
                translationX = dx.toFloat()
                translationY = dy.toFloat()
            },
    ) {
        // Animate between kinds of screens only (stage/rest/setup). The latest content
        // per kind is kept, so a track change stays inside the stage (its own transition)
        // and an outgoing screen keeps showing what it showed.
        val latest = remember { HashMap<String, Content>() }
        latest[content.key] = content
        // Once a switch has faded out, the screens left behind (a whole cover flow's scenes) go.
        LaunchedEffect(content.key) {
            delay(SCREEN_SWITCH_MS)
            latest.keys.retainAll(setOf(content.key))
        }
        AnimatedContent(
            targetState = content.key,
            transitionSpec = {
                fadeIn(tween(SCREEN_IN_MS, delayMillis = SCREEN_OUT_MS, easing = Motion.Dimmer)) togetherWith
                    fadeOut(tween(SCREEN_OUT_MS, easing = Motion.Dimmer))
            },
            label = "screen",
        ) { k ->
            when (val c = latest[k] ?: Content.Dark) {
                is Content.StageContent -> StageOnScreen(ui, c.stage, hooks, wall) { status, modelFor, intensity, linkDown ->
                    StageTheater(stage = c.stage, status = status, modelFor = modelFor, intensity = intensity, linkDown = linkDown, partyCode = ui.partyCode)
                }
                is Content.FlowContent -> StageOnScreen(ui, c.flow.stage, hooks, wall) { status, modelFor, intensity, linkDown ->
                    FlowTheater(flow = c.flow, status = status, modelFor = modelFor, intensity = intensity, linkDown = linkDown, partyCode = ui.partyCode)
                }
                is Content.RestContent -> RestScreen(
                    reason = c.reason,
                    // A group is named here too: "Living room + 5 · Paused".
                    speaker = c.speaker?.let { name -> SpeakerLabel.group(ui.source.group, t).takeIf { ui.display.group } ?: name },
                    entityId = ui.entityId,
                    nowMs = wall.value,
                    intensity = ui.display.intensity.factor,
                    appearance = c.appearance,
                )
                is Content.SetupContent -> {
                    val scene = rememberOffMain(c.info, screen, c.appearance) { SetupArt.scene(c.info, screen, c.appearance) }
                    if (scene != null) SetupScreen(c.reason, c.info, scene) else Box(Modifier.fillMaxSize())
                }
                Content.Dark -> Box(Modifier.fillMaxSize())
            }
        }
        // The quick settings rise from the bottom edge over whatever shows; the settings menu lies
        // over everything. Both come and go on their own. While the queue display just chosen is
        // being prepared, dots pulse after their Queue value.
        val pending = ui.queuePending != null
        QuickBarOverlay(ui.quick, ui.appearance, pending = pending)
        MenuOverlay(ui.menu, ui.appearance, pending = pending)
    }
}

private sealed interface Content {
    val key: String

    data class StageContent(val stage: StageData) : Content {
        // One stage for all tracks: track changes are animated inside the theater.
        override val key = "stage"
    }

    data class FlowContent(val flow: FlowData) : Content {
        // The cover flow animates its own changes, too; a switch to or from it fades through the background.
        override val key = "flow"
    }

    data class RestContent(val reason: RestReason, val speaker: String?, val appearance: Appearance) : Content {
        // A change of background fades like a new screen.
        override val key = "rest:$reason:$appearance"
    }

    data class SetupContent(val reason: SetupReason, val appearance: Appearance, val info: SetupInfo?) : Content {
        // While the page fades out it keeps its code and address, though the server has stopped.
        override val key = "setup:$reason:$appearance"
    }

    data object Dark : Content {
        override val key = "dark"
    }
}

private const val QUIET_START_MS = 4_000L

/** A new Intensity setting takes this long to turn the light to it (ms). */
private const val INTENSITY_MS = 1_200

/** The hint for the quick settings comes this long after the stage, and stays this long. */
private const val HINT_AFTER_MS = 3_000L
private const val HINT_MS = 12_000L

/**
 * How far the whole screen drifts against burn-in (dp): enough to soften every edge on an
 * OLED, too little to see anything stand off the middle.
 */
private const val DRIFT_DP = 6f

/** The screen fades in from black at start over this long (ms). */
private const val ENTRANCE_MS = 1_800

/** A switch of screens: how long the old screen takes to fade out (ms). The new one fades in after it. */
private const val SCREEN_OUT_MS = 900

/** A switch of screens: how long the new screen takes to fade in (ms), once the old one is out. */
private const val SCREEN_IN_MS = 1_300

/** A switch of screens is over after this long: both fades, with room to spare. */
private const val SCREEN_SWITCH_MS = 3_000L

/** How often the time is read (ms): the status line needs minutes, the drift whole pixels. */
private const val CLOCK_TICK_MS = 5_000L

private fun contentOf(ui: UiState, quietStart: Boolean): Content = when (val s = ui.screen) {
    is Screen.Stage -> {
        // Cover Flow or the single cover, as the queue shows, once its first scene is made;
        // until then what showed before stays.
        val flow = ui.flow?.let { Content.FlowContent(it) }
        val single = ui.stage?.let { Content.StageContent(it) }
        (if (ui.queue == QueueDisplay.FLOW) flow ?: single else single ?: flow) ?: Content.Dark
    }
    is Screen.Rest ->
        if (quietStart && s.reason == RestReason.CONNECTING) Content.Dark
        else Content.RestContent(s.reason, s.speaker, ui.appearance)
    is Screen.Setup -> Content.SetupContent(s.reason, ui.appearance, ui.setup)
}

/** A theater (the single cover's or Cover Flow's), given the player's status, how to build a model, the light setting and a lost link. */
private typealias Theater = @Composable (
    status: PlayerStatus,
    modelFor: (StageData) -> StageModel,
    intensity: () -> Float,
    linkDown: Boolean,
) -> Unit

/**
 * What the single cover and Cover Flow share for the [stage] on screen: the status line's words,
 * the models, the light setting. Then [theater] draws.
 */
@Composable
private fun StageOnScreen(ui: UiState, stage: StageData, hooks: RootHooks, wall: State<Long>, theater: Theater) {
    val stageScreen = ui.screen as? Screen.Stage
    // While a new cover loads, the previous stage stays; status follows the live state.
    // A lost connection (after the grace period) or a muted speaker dims the stage.
    val linkDown = stageScreen?.linkDown == true
    val status = stageScreen?.status ?: PlayerStatus.PAUSED
    val display = ui.display
    val peek = rememberPeek(hooks)
    val hint = rememberHint(ui.downHint)
    val t = texts()
    val statusLine = statusLineOf(ui, status, peek, wall.value, t)
    // A station has no next song to tell (what its queue holds next is another station).
    val nextTrack = stageScreen?.next?.takeIf { display.next && stageScreen.track.kind != MediaKind.RADIO }
    val chapter = ui.chapter
    // The bar shows by setting, or for a moment after a key; beside lyrics only then.
    fun barWanted(lyrics: Lyrics?) = peek || (lyrics == null && display.progressBar)
    // A stage still on screen after its song has ended (the next scene still being made, or its
    // lines leaving) keeps its own time, next and chapter; only the song that plays reads them live.
    val playingKey = stageScreen?.track?.key
    val playingNow by rememberUpdatedState(playingKey)
    val played = remember { PlayedLines() }
    val modelFor: (StageData) -> StageModel = remember(playingKey, status, nextTrack, statusLine, linkDown, display, peek, chapter, hint, t, hooks) {
        { s: StageData ->
            val key = s.track.key
            val plays = key == playingKey
            val text = TrackText.from(s.track, t[Strings.artistsOthers])
            // A book or an episode with chapters: the chapter in the album's place, the bar over
            // the chapter, the next chapter after it.
            val chapterPlace = played.chapter(key, plays, chapter)
            val nextKept = played.next(key, plays, nextTrack)
            val nextChapter = chapterPlace?.next?.takeIf { display.next }
            // The song's words: the song itself where "Up next" stands, the bar only for a moment.
            val lyrics = s.lyrics
            // The queue as a list, which names what comes next itself.
            val queueList = s.queue?.takeIf { lyrics == null }
            // The hint takes the place of what comes next (beside the song's words the song stays,
            // and the list names what comes next itself).
            val showHint = hint && lyrics == null && queueList == null
            StageModel(
                scene = s.scene,
                text = if (display.album) text else text.copy(album = null),
                status = status,
                durationMs = chapterPlace?.let { it.endMs - it.startMs } ?: s.track.durationMs,
                // Read for this song by name: the player reports the next a moment before the screen shows it.
                positionMs = { played.position(key, key == playingNow) { hooks.positionOf(key) } },
                statusLine = statusLine,
                year = s.track.year.takeIf { display.album },
                barShown = barWanted(lyrics),
                barStays = display.progressBar,
                slotText = StageTexts.slotText(linkDown, s.track, t),
                footer = when {
                    showHint -> t[Strings.hintDown]
                    lyrics != null -> StageTexts.footer(s.track, t)
                    queueList != null -> null
                    else -> nextChapter ?: StageTexts.footer(nextKept, t)
                },
                footerTitle = when {
                    showHint -> null
                    lyrics != null -> StageTexts.footerTitle(s.track, t)
                    queueList != null -> null
                    nextChapter != null -> null
                    else -> StageTexts.footerTitle(nextKept, t)
                },
                footerLead = if (showHint) t[Strings.hintDownKey] else null,
                chapter = chapterPlace?.label,
                barStartMs = chapterPlace?.startMs ?: 0L,
                lyrics = lyrics,
                queue = queueList?.let { QueueModel.of(it, t) },
            )
        }
    }
    // Changing the light's strength in the menu turns it like a dimmer.
    val intensity by animateFloatAsState(
        targetValue = display.intensity.factor,
        animationSpec = tween(INTENSITY_MS, easing = Motion.Dimmer),
        label = "light-setting",
    )
    // The cover's colors move only while music plays (a pause holds them, like everything else).
    val moving = display.motion != MotionLevel.OFF && status == PlayerStatus.PLAYING && !linkDown
    val fieldTime = remember(moving, hooks) { FieldTime(hooks.fieldTime, moving) }
    // The bar moves when its length is known and nothing stands in its place.
    val barMoves = barWanted(stage.lyrics) && !linkDown && (chapter != null || stage.track.durationMs != null)
    // The stage's tick: fast while music plays. With nothing to redraw, or with the settings
    // covering the stage, it stops.
    val tick = rememberTickLoop(
        fast = status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING,
        running = (moving || barMoves || stage.lyrics != null) && ui.menu == null,
    )
    CompositionLocalProvider(LocalFieldTime provides fieldTime, LocalStageTick provides tick) {
        theater(status, modelFor, { intensity }, linkDown)
    }
}

/** Whether hidden parts show again for a moment, after a key press ([RootHooks.peekUntil]). */
@Composable
private fun rememberPeek(hooks: RootHooks): Boolean {
    val until = hooks.peekUntil.value
    var peek by remember { mutableStateOf(false) }
    LaunchedEffect(until) {
        val left = until - hooks.now()
        if (left > 0) {
            peek = true
            delay(left)
        }
        peek = false
    }
    return peek
}

/**
 * Whether the hint for the quick settings shows: until they have been opened once ([downHint]),
 * for a while each time the stage comes, and in a still at once.
 */
@Composable
private fun rememberHint(downHint: Boolean): Boolean {
    val still = LocalStill.current
    var hinting by remember { mutableStateOf(false) }
    LaunchedEffect(downHint) {
        hinting = false
        if (!downHint || still) return@LaunchedEffect
        delay(HINT_AFTER_MS)
        hinting = true
        delay(HINT_MS)
        hinting = false
    }
    return if (still) downHint else hinting
}

/** The status line for [ui]: the clock at [nowMs], the custom values and the lead, the hidden parts back while [peek]. */
private fun statusLineOf(ui: UiState, status: PlayerStatus, peek: Boolean, nowMs: Long, t: Texts): StatusLine {
    val display = ui.display
    val player = ui.source.player
    val clock = if (display.clock || peek) t.clock(nowMs) else null
    // Music from another room is always named by it, since the screen doesn't hang there.
    val room = player?.friendlyName?.let(SpeakerLabel::roomName)?.takeIf { ui.source.elsewhere }
    val group = SpeakerLabel.group(ui.source.group, t).takeIf { display.group || peek } ?: room
    val volume = SpeakerLabel.volume(player?.volume, t)
    // How it plays: when the sleep timer pauses it, else a book's or an episode's speed.
    val spoken = (ui.screen as? Screen.Stage)?.track?.kind?.spoken == true
    val pace = ui.sleepAt?.let { StageTexts.sleep(it, t) } ?: TimeText.speed(player?.speed ?: 1f, t)?.takeIf { spoken }
    val own = ui.source.speaker != null && ui.source.speaker == ui.source.ownSpeaker
    val volumeAlways = StageTexts.volumeAlways(display.volume == VolumeDisplay.ALWAYS, own, player?.volume)
    val lead = StageTexts.lead(status, player?.muted == true, ui.volumeFlash, volume, group, volumeAlways = volumeAlways, t = t, pace = pace)
    // The values of one's own, after the time (hidden by setting, back for a moment on a key).
    return StatusLine(lead, clock, ui.values.takeIf { display.values || peek }.orEmpty())
}
