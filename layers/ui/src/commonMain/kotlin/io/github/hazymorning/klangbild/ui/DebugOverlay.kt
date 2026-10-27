package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.klangbild.sound.SoundFeed
import io.github.hazymorning.klangbild.sound.SoundFrame
import io.github.hazymorning.klangbild.sound.SoundTiming
import io.github.hazymorning.klangbild.sound.monotonicMicros
import io.github.hazymorning.klangbild.text.Strings
import kotlinx.coroutines.flow.StateFlow

/**
 * One second of frames on the real device (`--ez debug true`): how many were drawn, how long
 * the slowest took and how many missed a 60 Hz deadline. Nothing is drawn when nothing changes,
 * so a quiet screen shows 0 frames, as intended.
 */
data class FrameSecond(val frames: Int, val worstMs: Float, val janky: Int)

/** The frames of each second, as the platform counts them ([frames]), and what [sound] hears. */
@Composable
fun DebugOverlay(frames: StateFlow<FrameSecond>, sound: SoundFeed) {
    val s by frames.collectAsStateWithLifecycle()
    val t = texts()
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Column(Modifier.background(BACKDROP).padding(horizontal = 8.dp, vertical = 4.dp)) {
            BasicText(t.format(Strings.debugFrames, s.frames, t.decimal(s.worstMs.toDouble(), 1), s.janky), style = TextStyle(color = INK, fontSize = 12.sp))
            SoundMeter(sound)
        }
    }
}

/**
 * What the sound feed hears at each moment, so its timing can be judged against the music on a
 * TV: how exactly it is timed, then bars for the loudness, the four bands and the onset, and a
 * dot that lights for a moment on each beat. The feed is read only while the meter shows, and
 * the meter is drawn on every frame then (it shows only with the overlay).
 */
@Composable
private fun SoundMeter(sound: SoundFeed) {
    DisposableEffect(sound) {
        sound.reading(true)
        onDispose { sound.reading(false) }
    }
    val frames = rememberTick(fast = true)
    val heard = remember { SoundFrame() }
    val t = texts()
    val measurer = rememberTextMeasurer()
    val words = remember(t, measurer) {
        val style = TextStyle(color = INK, fontSize = 12.sp)
        mapOf(
            SoundTiming.EXACT to measurer.measure(t[Strings.debugSoundExact], style),
            SoundTiming.CLOSE to measurer.measure(t[Strings.debugSoundClose], style),
            SoundTiming.NONE to measurer.measure(t[Strings.debugSoundNone], style),
        )
    }
    Canvas(Modifier.padding(top = 4.dp).size(METER_WIDTH, METER_HEIGHT)) {
        frames.value
        val nowUs = monotonicMicros()
        sound.read(nowUs, heard)
        drawText(words.getValue(heard.timing), topLeft = Offset.Zero)
        val bar = BAR.toPx()
        val gap = BAR_GAP.toPx()
        var x = LABEL_WIDTH.toPx()
        val levels = 2 + heard.bands.size
        for (i in 0 until levels) {
            val level = when (i) {
                0 -> heard.loudness
                levels - 1 -> heard.onset
                else -> heard.bands[i - 1]
            }
            drawRect(TRACK, Offset(x, 0f), Size(bar, size.height))
            drawRect(INK, Offset(x, size.height * (1 - level)), Size(bar, size.height * level))
            x += bar + gap
        }
        val sinceBeat = nowUs - heard.beatUs
        val lit = heard.beatUs != SoundFrame.NO_BEAT && sinceBeat in 0..BEAT_LIT_US
        drawCircle(if (lit) (if (heard.downbeat) DOWNBEAT else INK) else TRACK, radius = bar, center = Offset(x + bar, size.height / 2))
    }
}

private val INK = Color(0xFFB0A090)
private val DOWNBEAT = Color(0xFFE0C8A0)
private val TRACK = Color(0x33B0A090)
private val BACKDROP = Color(0x99000000)
private val METER_WIDTH = 200.dp
private val METER_HEIGHT = 24.dp
private val LABEL_WIDTH = 96.dp
private val BAR = 6.dp
private val BAR_GAP = 4.dp

/** A beat lights the dot this long. */
private const val BEAT_LIT_US = 120_000L
