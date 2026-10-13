package dev.fitnesstimer.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fitnesstimer.nowplaying.NowPlaying
import dev.fitnesstimer.render.ArtColors
import dev.fitnesstimer.render.ArtworkTile
import dev.fitnesstimer.render.NegativeTimerText
import dev.fitnesstimer.render.WaveProgress

/**
 * Companion-mode body (PLAN.md section N), styled after One UI's media
 * player: color-driven from the artwork, title/artist overlaid on the art,
 * a waveform progress bar, no visible transport buttons (this app is
 * gesture-driven; the timer stays under the player).
 *
 * Lives inside MainScreen's gesture Box, so it must not contain any clickable
 * elements (a tap here would also fire the timer's tap-to-start gesture);
 * the interactive onboarding card is a separate overlay, outside that Box.
 */
@Composable
fun CompanionBody(
    nowPlaying: NowPlaying?,
    accessGranted: Boolean,
    colors: ArtColors,
    timerText: () -> String,
    holdProgress: () -> Float,
    fakeWave: Boolean = false,
) {
    Box(Modifier.fillMaxSize()) {
        if (nowPlaying != null) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Captured here: BoxWithConstraintsScope's maxWidth/maxHeight aren't
                // reachable from inside the nested Column (DSL scope marker).
                val tileMaxWidth = maxWidth * 0.88f
                val tileMaxHeight = maxHeight * 0.46f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtworkTile(nowPlaying.artwork, tileMaxWidth, tileMaxHeight) {
                        // Scrim so the overlaid text stays readable on any artwork.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(110.dp)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
                        )
                        Column(
                            Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                        ) {
                            Text(
                                nowPlaying.title ?: "Unknown title",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = listOfNotNull(nowPlaying.artist, nowPlaying.album).joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    WaveProgress(nowPlaying, colors.accent, Modifier.width(tileMaxWidth), fake = fakeWave)
                    Spacer(Modifier.height(4.dp))
                    NegativeTimerText(
                        text = timerText,
                        holdProgress = holdProgress,
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                    )
                }
            }
        } else {
            if (accessGranted) {
                Text(
                    "Nothing playing — start music in any app",
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                )
            }
            NegativeTimerText(text = timerText, holdProgress = holdProgress, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Onboarding for notification access. Explains exactly what is read, opens
 * the system settings page, and mentions the Android 13+ "restricted
 * setting" unblock that sideloaded installs may hit (PLAN.md N, DECISIONS.md).
 * A separate overlay (not inside the gesture Box) so its buttons don't also
 * trigger the timer gestures.
 */
@Composable
fun NotificationAccessCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1C))
                .padding(16.dp),
        ) {
            Text("Show what's playing", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Allow notification access so this app can show the song, artwork and progress from " +
                    "your music apps and control them. It only reads media playback info — never your " +
                    "notifications — and nothing leaves your phone.",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "If Android says \"Restricted setting\": open this app's App info, tap ⋮ (top right), " +
                    "choose \"Allow restricted settings\", then try again.",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = { openNotificationAccessSettings(context) }) { Text("Open settings") }
            }
        }
    }
}

private fun openNotificationAccessSettings(context: android.content.Context) {
    val primary = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(primary)
    } catch (e: ActivityNotFoundException) {
        // Some ROMs lack the dedicated page; App info is the fallback.
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * One-time explanation shown BEFORE the system "record audio" dialog for the
 * beat-reactive wave. The permission name sounds alarming, so say plainly what
 * it is used for and what is not done. Separate overlay (not inside the gesture Box).
 */
@Composable
fun BeatAccessCard(onEnable: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1C))
                .padding(16.dp),
        ) {
            Text("Make the wave react to your music", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "To follow the beat, the wave uses Android's audio visualizer, which Android gates behind " +
                    "the \"Record audio\" permission. This app does not record, save or send any audio and " +
                    "never uses the microphone — it only measures how loud the low, mid and high sounds are, live.",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = onEnable) { Text("Enable") }
            }
        }
    }
}

/**
 * Shown when audio access is granted and companion audio is playing, but the
 * visualizer keeps reporting silence (AudioLevelSource.Status.SILENT): the
 * wave falls back to a faint idle ripple, which reads as broken rather than
 * "no data available". By the time this shows, [AudioLevelSource] has already
 * failed on the global mix (session 0) AND on its scan for the playing track's
 * own audio session. Known causes (DECISIONS.md): hardware audio offload
 * (confirmed 2026-09-22) — compressed audio decoded on a low-power DSP path
 * that bypasses AudioFlinger's mixer entirely — and system spatial-audio
 * effects such as Xiaomi's "Immersive sound" (2026-10-13), which the track
 * session scan normally gets around. Both happen over Bluetooth *and* straight
 * out of the phone speaker, so it is not Bluetooth-specific and
 * [bluetoothActive] (from [dev.fitnesstimer.nowplaying.isBluetoothAudioOutputActive])
 * picks accurate wording instead of blaming Bluetooth when the phone is
 * playing through its own speaker. Separate overlay (not inside the gesture
 * Box).
 */
@Composable
fun BeatSilentHintCard(bluetoothActive: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1C))
                .padding(16.dp),
        ) {
            Text("Wave isn't reacting to the music", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (bluetoothActive) {
                    "Audio access is on, but Android isn't handing this app any sound to measure. This phone is " +
                        "on Bluetooth right now, which often routes audio through a battery-saving hardware path " +
                        "that skips the system effects the wave reads. Worth trying: disable \"Bluetooth A2DP " +
                        "hardware offload\" in Developer options, and if your phone has a spatial-audio effect " +
                        "(on Xiaomi/POCO: Immersive sound), try switching it off. SetBeat already tried the " +
                        "music app's own audio too, so if the wave stays flat it's the player's own decode path."
                } else {
                    "Audio access is on, but Android isn't handing this app any sound to measure — and this isn't " +
                        "a Bluetooth thing, since nothing's connected right now. SetBeat already tried the music " +
                        "app's own audio too. If your phone has a spatial-audio effect (on Xiaomi/POCO: Sound " +
                        "effects → Immersive sound), try switching it off. Some music apps also decode audio " +
                        "through a battery-saving hardware path that no app can read, which depends on the " +
                        "player and sometimes even the track."
                },
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Got it") }
            }
        }
    }
}
