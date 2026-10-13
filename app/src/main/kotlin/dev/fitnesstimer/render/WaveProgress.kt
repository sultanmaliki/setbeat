package dev.fitnesstimer.render

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.fitnesstimer.nowplaying.AudioLevelSource
import dev.fitnesstimer.nowplaying.BAND_COUNT
import dev.fitnesstimer.nowplaying.NowPlaying
import dev.fitnesstimer.nowplaying.extrapolatePosition
import dev.fitnesstimer.nowplaying.progressFraction
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/** "m:ss" (or "h:mm:ss"); "--:--" when unknown. */
fun formatClock(totalSeconds: Long): String {
    if (totalSeconds < 0) return "--:--"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

// History must reach across the whole bar: at 4 dp per column, 160 columns = 640 dp, wider than any
// phone in portrait. With only 60 (240 dp) the wave stopped short of the left end once the thumb had
// moved more than 240 dp along the bar (about 70% into a song).
private const val COLUMNS = 160
private const val TICK_MS = 33L
private const val FAKE_BEAT_MS = 469.0 // ~128 bpm, a common dance-music tempo

/**
 * Vibes edition (DECISIONS.md): a procedural stand-in for real audio analysis,
 * used only by the `vibes` edition, which has no RECORD_AUDIO permission at all. Driven by playback
 * position (not wall-clock time) so it's deterministic and loops back cleanly on seek/repeat — not
 * an attempt to reflect the actual track, just a plausible-looking beat pulse.
 */
private fun fakeBeatTarget(positionMs: Long, band: Int): Float {
    // positionAt() returns -1 when the position is unknown; fall back to a steady walking clock
    // so the wave still animates instead of freezing.
    val t = if (positionMs >= 0) positionMs else SystemClock.elapsedRealtime()
    val phase = ((t % FAKE_BEAT_MS.toLong()) / FAKE_BEAT_MS).toFloat()
    val kick = (1f - phase).let { it * it * it } // sharp attack, cubic decay — a "kick drum" pulse
    return when (band) {
        0 -> 0.30f + 0.60f * kick
        1 -> 0.25f + 0.35f * abs(sin(t / 180.0 + 1.0)).toFloat() + 0.15f * kick
        else -> 0.15f + 0.30f * abs(sin(t / 90.0 + 2.0)).toFloat()
    }
}

/**
 * One UI-style reactive waveform progress (PLAN.md N.5e). Three translucent
 * filled "mountain" bands (low / mid / high frequencies, in analogous hues
 * taken from the album art) scroll leftwards from the thumb, so the newest
 * audio is always at the current position; a white ring marks the position
 * and a dim line the remainder. The heights come from the real output-mix
 * audio via [AudioLevelSource] (real-time beat/low/mid/high reaction).
 *
 * If the audio capture isn't available (permission not granted, blocked on
 * this device, or nothing audible), the bands fall back to a faint idle
 * ripple: honest "no data" rather than fake beats. Paused: bands ease to flat.
 *
 * Performance (see PLAN.md N.5c): one ~30 Hz ticker only while playing, all
 * geometry computed in the Canvas draw lambda on its own graphics layer (a
 * tick must not re-record the whole screen); time labels recompose once per
 * second; zero frames while paused.
 */
@Composable
fun WaveProgress(np: NowPlaying, accent: Color, modifier: Modifier = Modifier, fake: Boolean = false) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val history = remember { Array(BAND_COUNT) { FloatArray(COLUMNS) } }
    val shown = remember { FloatArray(BAND_COUNT) } // per-tick smoothing between capture callbacks
    fun positionAt(t: Long) =
        extrapolatePosition(np.positionMs, np.positionUpdateElapsedMs, np.speed, np.isPlaying, t, np.durationMs)
    LaunchedEffect(np.isPlaying, np.positionUpdateElapsedMs, np.positionMs, fake) {
        now = SystemClock.elapsedRealtime()
        while (np.isPlaying) {
            delay(TICK_MS)
            val t = SystemClock.elapsedRealtime()
            // Read the status HERE, every tick. It used to be captured once when this effect
            // started; if the audio capture came up a moment later (it restarts at each track
            // change) the loop stayed stuck in "no audio" mode and the wave shrank to a faint
            // ripple until an unrelated playback update restarted the effect.
            val live = !fake && AudioLevelSource.status.value == AudioLevelSource.Status.ACTIVE
            val source = AudioLevelSource.levels
            for (b in 0 until BAND_COUNT) {
                val arr = history[b]
                System.arraycopy(arr, 1, arr, 0, COLUMNS - 1)
                val target = when {
                    fake -> fakeBeatTarget(positionAt(t), b)
                    live -> source[b]
                    else -> 0.07f + 0.05f * sin((t / 350.0 + b * 1.7).toFloat()) // faint idle ripple, not fake beats
                }
                // Capture arrives ~20x/s but columns scroll ~30x/s: ease toward it so
                // the shape is smooth rather than stair-stepped.
                shown[b] += (target - shown[b]) * 0.5f
                arr[COLUMNS - 1] = shown[b]
            }
            now = t
        }
    }
    val amplitude by animateFloatAsState(if (np.isPlaying) 1f else 0f, tween(450), label = "waveAmplitude")
    val elapsedSeconds by remember(np) { derivedStateOf { positionAt(now).let { if (it < 0) -1L else it / 1000 } } }

    // Analogous band colors from the art accent: warm / mid / cool.
    val (hue, sat, _) = rgbToHsl(accent)
    val s = if (sat < 0.1f) 0f else 0.85f
    val lowColor = hslToColor(hue, s, 0.62f)
    val midColor = hslToColor((hue + 50f) % 360f, s, 0.6f)
    val highColor = hslToColor((hue + 310f) % 360f, s, 0.6f)
    val bandColors = listOf(lowColor, midColor, highColor)
    val bandScale = floatArrayOf(1.0f, 0.8f, 0.62f) // bass tallest, highs lowest, like the reference

    Column(modifier) {
        // graphicsLayer: without its own layer every tick re-records the draw ops of
        // the whole screen (artwork, gradient, text) - measured on device.
        Canvas(Modifier.fillMaxWidth().height(48.dp).graphicsLayer()) {
            val w = size.width
            val baseY = size.height * 0.68f
            val maxAmp = baseY - 3.dp.toPx()
            val fraction = progressFraction(positionAt(now), np.durationMs)
            val thumbX = w * (fraction ?: 0f)
            val colW = 4.dp.toPx()
            val taper = 28.dp.toPx()
            val amp = maxAmp * amplitude

            // Remainder line (dim) and played line (bright).
            drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, baseY), Offset(w, baseY), 3.dp.toPx(), StrokeCap.Round)

            // Bands: newest column at the thumb, older ones scroll left.
            for (b in 0 until BAND_COUNT) {
                val arr = history[b]
                val path = Path()
                var started = false
                var lastX = thumbX
                var prevX = thumbX
                var prevY = baseY
                for (i in 0 until COLUMNS) {
                    val x = thumbX - (COLUMNS - 1 - i) * colW
                    if (x < 0f) continue
                    // Taper to nothing at both ends (at the thumb and at the left edge) so the
                    // hills settle into the line instead of ending in a hard vertical cut.
                    val edge = minOf(thumbX - x, x) / taper
                    val env = edge.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                    val y = baseY - (arr[i] * bandScale[b] * amp * env).coerceAtLeast(0f)
                    if (!started) {
                        path.moveTo(x, baseY)
                        path.lineTo(x, y)
                        started = true
                    } else {
                        // Quadratic through midpoints: smooth hills instead of a jagged polyline.
                        path.quadraticTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                    }
                    prevX = x
                    prevY = y
                    lastX = x
                }
                if (started) {
                    path.lineTo(lastX, baseY)
                    path.close()
                    drawPath(
                        path,
                        Brush.verticalGradient(
                            listOf(bandColors[b].copy(alpha = 0.9f), bandColors[b].copy(alpha = 0.25f)),
                            startY = baseY - amp,
                            endY = baseY,
                        ),
                    )
                }
            }
            drawLine(Color.White.copy(alpha = 0.9f), Offset(0f, baseY), Offset(thumbX, baseY), 3.dp.toPx(), StrokeCap.Round)

            // Ring thumb.
            if (fraction != null) {
                val c = Offset(thumbX, baseY)
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = c)
                drawCircle(Color.White, radius = 8.dp.toPx(), center = c, style = Stroke(width = 3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatClock(elapsedSeconds),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                formatClock(if (np.durationMs > 0) np.durationMs / 1000 else -1),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
