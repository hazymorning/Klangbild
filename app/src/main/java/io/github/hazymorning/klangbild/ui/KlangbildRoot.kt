package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.app.UiState
import io.github.hazymorning.klangbild.app.LandscapeUi
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.MotionLevel
import io.github.hazymorning.klangbild.config.VolumeDisplay
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SpeakerLabel
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.domain.TimeLine
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.text.LocalTexts
import io.github.hazymorning.klangbild.text.texts
import io.github.hazymorning.klangbild.ui.flow.FlowTheatre
import io.github.hazymorning.klangbild.ui.stage.FieldTime
import io.github.hazymorning.klangbild.ui.stage.LocalFieldTime
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import io.github.hazymorning.klangbild.ui.stage.StageTheatre
import io.github.hazymorning.klangbild.ui.stage.StatusLine
import io.github.hazymorning.klangbild.ui.landscape.LandscapeAhead
import io.github.hazymorning.klangbild.ui.landscape.releaseBakedLandscape
import io.github.hazymorning.klangbild.ui.landscape.LandscapeScreen
import io.github.hazymorning.klangbild.ui.landscape.labelSide
import io.github.hazymorning.klangbild.ui.landscape.rememberLandscapeFeed
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import kotlinx.coroutines.delay

/** A wall clock that ticks every [periodMs] (the UI needs minutes, the drift whole pixels). */
@Composable
fun rememberWallClock(periodMs: Long = 5_000, now: () -> Long = System::currentTimeMillis): State<Long> =
    produceState(initialValue = now()) {
        while (true) {
            value = now()
            delay(periodMs)
        }
    }

/** What the root needs from the outside world. */
class RootHooks(
    val positionNow: () -> Long?,
    /** Local time until which hidden parts (time, place, progress) show again after a key press. */
    val peekUntil: State<Long> = mutableLongStateOf(0L),
    val now: () -> Long = System::currentTimeMillis,
    /** The color field's time (seconds): it runs while music plays, at the motion setting's speed. */
    val fieldTime: () -> Double = { 0.0 },
    /** The moment the landscape's sky shows. */
    val landscapeClock: LandscapeClock = LandscapeClock(now),
)

/**
 * The whole screen. Switches between stage, rest and setup with a slow fade through the
 * dark, fades in from black on start (no white flash, ever) and lets everything drift a
 * few pixels for OLED care.
 */
@Composable
fun KlangbildRoot(ui: UiState, screen: ScreenSpec, hooks: RootHooks, modifier: Modifier = Modifier) {
    val wall = rememberWallClock(now = hooks.now)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(1_800, easing = Motion.Dimmer)) }

    // The first seconds after a start stay dark instead of flashing "Connecting":
    // usually the music is there before anyone could read that line.
    val quiet by produceState(initialValue = true) {
        delay(QUIET_START_MS)
        value = false
    }
    val content = contentKey(ui, quiet)
    CompositionLocalProvider(LocalTexts provides (ui.texts ?: texts())) {
        Root(ui, screen, hooks, wall, entrance, content, modifier)
    }
}

@Composable
private fun Root(ui: UiState, screen: ScreenSpec, hooks: RootHooks, wall: State<Long>, entrance: Animatable<Float, *>, content: Content, modifier: Modifier) {
    val density = LocalDensity.current.density
    val t = texts()
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                alpha = entrance.value
                val (dx, dy) = Motion.drift(wall.value, DRIFT_DP * density)
                translationX = dx.toFloat()
                translationY = dy.toFloat()
            },
    ) {
        // The landscape's picture is baked while it is only on its way, so it comes at once.
        ui.landscapeAhead?.let { LandscapeAhead(rememberLandscapeFeed(ui, hooks.landscapeClock, hooks.now), labelSide(it)) }
        // No longer coming: its picture is let go after a while, and baked anew the next time.
        if (ui.landscapeAhead == null && ui.landscape == null && !LocalStill.current) {
            LaunchedEffect(Unit) {
                delay(LANDSCAPE_RELEASE_MS)
                releaseBakedLandscape()
            }
        }
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
                fadeIn(tween(1_300, delayMillis = 900, easing = Motion.Dimmer)) togetherWith
                    fadeOut(tween(900, easing = Motion.Dimmer))
            },
            label = "screen",
        ) { k ->
            when (val c = latest[k] ?: Content.Dark) {
                is Content.StageContent -> StageContentView(ui, c.stage, hooks, wall) { status, modelFor, lightFactor, linkDown ->
                    StageTheatre(stage = c.stage, status = status, modelFor = modelFor, lightFactor = lightFactor, dimmed = linkDown, partyCode = ui.partyCode)
                }
                is Content.FlowContent -> StageContentView(ui, c.flow.stage, hooks, wall) { status, modelFor, lightFactor, linkDown ->
                    FlowTheatre(flow = c.flow, status = status, modelFor = modelFor, lightFactor = lightFactor, dimmed = linkDown, partyCode = ui.partyCode)
                }
                is Content.RestContent -> RestScreen(
                    reason = c.reason,
                    // A group is named here too: "Living room + 5 · Paused".
                    speaker = c.speaker?.let { name -> SpeakerLabel.group(ui.source.group, t).takeIf { ui.display.group } ?: name },
                    entityId = ui.entityId,
                    nowMs = wall.value,
                    lightFactor = ui.display.light.factor,
                    appearance = c.appearance,
                )
                is Content.LandscapeContent -> LandscapeScreen(ui, c.landscape, hooks.landscapeClock, hooks.positionNow, hooks.now)
                is Content.SetupContent -> {
                    val scene = rememberOffMain(ui.setup, screen, c.appearance) { SetupArt.scene(ui.setup, screen, c.appearance) }
                    if (scene != null) SetupScreen(c.reason, ui.setup, scene) else Box(Modifier.fillMaxSize())
                }
                Content.Dark -> Box(Modifier.fillMaxSize())
            }
        }
        // The quick settings rise from the bottom edge over whatever shows (over the landscape on
        // its dark ground); the settings menu lies over everything. Both come and go on their own.
        QuickBarOverlay(ui.quick, if (content is Content.LandscapeContent) Appearance.DARK else ui.appearance)
        MenuOverlay(ui.menu, ui.appearance)
    }
}

private sealed interface Content {
    val key: String

    data class StageContent(val stage: StageData) : Content {
        // One stage view for all tracks: track changes are animated inside the theater.
        override val key = "stage"
    }

    data class FlowContent(val flow: FlowData) : Content {
        // The cover flow animates its own changes, too; switching views fades through the dark.
        override val key = "flow"
    }

    data class RestContent(val reason: io.github.hazymorning.klangbild.domain.RestReason, val speaker: String?, val appearance: Appearance) : Content {
        // A new wall fades through the dark like a new screen.
        override val key = "rest:$reason:$appearance"
    }

    data class SetupContent(val reason: io.github.hazymorning.klangbild.domain.SetupReason, val appearance: Appearance) : Content {
        override val key = "setup:$reason:$appearance"
    }

    data class LandscapeContent(val landscape: LandscapeUi) : Content {
        // The landscape changes its label and its light inside; it comes and goes through the dark.
        override val key = "landscape"
    }

    data object Dark : Content {
        override val key = "dark"
    }
}

private const val QUIET_START_MS = 4_000L

/** The hint for the quick settings comes this long after the stage, and stays this long. */
private const val HINT_AFTER_MS = 3_000L
private const val HINT_MS = 12_000L

/** The landscape's picture is kept this long after it stopped coming (a pause cut short, say). */
private const val LANDSCAPE_RELEASE_MS = 10_000L

/**
 * How far the whole screen drifts against burn-in (dp): enough to soften every edge on an
 * OLED, too little to see anything stand off the middle.
 */
private const val DRIFT_DP = 6f

/** A switch of screens is over after this long (900 ms out, then 1.3 s in after 900 ms), with room to spare. */
private const val SCREEN_SWITCH_MS = 3_000L

private fun contentKey(ui: UiState, quietStart: Boolean): Content = if (ui.landscape != null) Content.LandscapeContent(ui.landscape) else when (val s = ui.screen) {
    is Screen.Stage ->
        if (ui.view == StageView.FLOW) ui.flow?.let { Content.FlowContent(it) } ?: Content.Dark
        else ui.stage?.let { Content.StageContent(it) } ?: Content.Dark
    is Screen.Rest ->
        if (quietStart && s.reason == io.github.hazymorning.klangbild.domain.RestReason.CONNECTING) Content.Dark
        else Content.RestContent(s.reason, s.speaker, ui.appearance)
    is Screen.Setup -> Content.SetupContent(s.reason, ui.appearance)
}

/** A view's theater, given the player's status, how to build a model, the light setting and a lost link. */
private typealias Theatre = @Composable (
    status: PlayerStatus,
    modelFor: (StageData) -> StageModel,
    lightFactor: () -> Float,
    linkDown: Boolean,
) -> Unit

/**
 * What both views share for the [stage] that plays: the status line's words, the models, the
 * light setting. Then [theatre] draws.
 */
@Composable
private fun StageContentView(ui: UiState, stage: StageData, hooks: RootHooks, wall: State<Long>, theatre: Theatre) {
    val stageScreen = ui.screen as? Screen.Stage
    // While a new cover loads, the previous stage stays; status follows the live state.
    // A lost connection (after the grace period) or a muted speaker dims the stage.
    val linkDown = stageScreen?.linkDown == true
    val status = stageScreen?.status ?: PlayerStatus.PAUSED
    val display = ui.display
    val player = ui.source.player
    val muted = player?.muted == true
    // A key press brings hidden parts back for a few seconds.
    val peekUntil = hooks.peekUntil.value
    var peek by remember { mutableStateOf(false) }
    LaunchedEffect(peekUntil) {
        val left = peekUntil - hooks.now()
        if (left > 0) {
            peek = true
            delay(left)
        }
        peek = false
    }
    // Until the quick settings have been opened once, the bottom line tells how for a while each
    // time the stage comes (in a still at once).
    val still = LocalStill.current
    var hinting by remember { mutableStateOf(false) }
    LaunchedEffect(ui.downHint) {
        hinting = false
        if (!ui.downHint || still) return@LaunchedEffect
        delay(HINT_AFTER_MS)
        hinting = true
        delay(HINT_MS)
        hinting = false
    }
    val hint = if (still) ui.downHint else hinting
    val t = texts()
    val clock = if (display.clock || peek) t.clock(wall.value) else null
    val group = SpeakerLabel.group(ui.source.group, t).takeIf { display.group || peek }
    val volume = SpeakerLabel.volume(player?.volume, muted, t)
    // How it plays: when the sleep timer pauses it, else a book's or an episode's speed.
    val spoken = stageScreen?.track?.kind?.spoken == true
    val pace = ui.sleepAt?.let { StageTexts.sleep(it, t) } ?: TimeLine.speed(player?.speed ?: 1f, t)?.takeIf { spoken }
    val lead = StageTexts.lead(status, muted, ui.volumeFlash, volume, group, volumeAlways = display.volume == VolumeDisplay.ALWAYS, t = t, pace = pace)
    // The values of one's own, after the time (hidden by setting, back for a moment on a key).
    val statusLine = StatusLine(lead, clock, ui.values.takeIf { display.values || peek }.orEmpty())
    // A station has no next song to tell (what its queue holds next is another station).
    val nextTrack = stageScreen?.next?.takeIf { display.next && stageScreen.track.kind != MediaKind.RADIO }
    val chapter = ui.chapter
    val modelFor: (StageData) -> StageModel = remember(stage, status, nextTrack, statusLine, linkDown, display, peek, chapter, hint, t) {
        { s: StageData ->
            val text = TrackText.from(s.track, t[R.string.artists_others])
            // A book or an episode with chapters: the chapter in the album's place, the bar over
            // the chapter, the next chapter after it.
            val ch = chapter?.takeIf { s.track.key == stage.track.key }
            val nextChapter = ch?.next?.takeIf { display.next }
            // The song's words: the song itself where "Up next" stands, the bar only for a moment.
            val lyrics = s.lyrics
            // The hint takes the place of what comes next (beside the song's words the song stays).
            val tip = hint && lyrics == null
            StageModel(
                scene = s.scene,
                text = if (display.album) text else text.copy(album = null),
                status = status,
                durationMs = ch?.let { it.endMs - it.startMs } ?: s.track.durationMs,
                positionMs = { if (s.track.key == stage.track.key) hooks.positionNow() else null },
                year = s.track.year.takeIf { display.album },
                progress = if (lyrics != null) peek else display.timeLine || peek,
                line = StageTexts.line(linkDown, s.track, t),
                next = when {
                    tip -> t[R.string.hint_down]
                    lyrics != null -> StageTexts.next(s.track, t)
                    else -> nextChapter ?: StageTexts.next(nextTrack, t)
                },
                nextTitle = when {
                    tip -> null
                    lyrics != null -> StageTexts.nextTitle(s.track, t)
                    nextChapter != null -> null
                    else -> StageTexts.nextTitle(nextTrack, t)
                },
                nextLead = if (tip) t[R.string.hint_down_key] else null,
                statusLine = statusLine,
                chapter = ch?.label,
                rangeStartMs = ch?.startMs ?: 0L,
                lyrics = lyrics,
            )
        }
    }
    // Changing the light's strength in the menu turns it like a dimmer.
    val lightFactor by animateFloatAsState(
        targetValue = display.light.factor,
        animationSpec = tween(1_200, easing = Motion.Dimmer),
        label = "light-setting",
    )
    // The cover's colors move only while music plays (a pause holds them, like everything else).
    val moving = display.motion != MotionLevel.OFF && status == PlayerStatus.PLAYING && !linkDown
    val fieldTime = remember(moving, hooks) { FieldTime(hooks.fieldTime, moving) }
    // The bar shows when its length is known and nothing stands in its place, by setting or for a
    // moment (beside the song's words only for a moment).
    val words = stage.lyrics != null
    val barShown = (if (words) peek else display.timeLine || peek) && !linkDown && (chapter != null || stage.track.durationMs != null)
    // One tick for the bar, the colors and the words, so they are redrawn in the same frames. With
    // nothing to redraw, or with the settings covering the stage, it stops.
    val tick = rememberStageTick(
        fast = moving || status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING,
        running = (moving || barShown || words) && ui.menu == null,
    )
    CompositionLocalProvider(LocalFieldTime provides fieldTime, LocalStageTick provides tick) {
        theatre(status, modelFor, { lightFactor }, linkDown)
    }
}
