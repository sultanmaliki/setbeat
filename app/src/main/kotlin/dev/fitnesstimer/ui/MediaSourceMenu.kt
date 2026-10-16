package dev.fitnesstimer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PLAN.md section E's "top-right tap = media source picker", expanded per
 * user request 2026-09-20: one file was too limiting once playlists exist.
 * A tap on the scrim outside the menu dismisses it.
 */
@Composable
fun MediaSourceMenu(
    onDismiss: () -> Unit,
    onChooseFile: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onNowPlaying: () -> Unit,
    showNowPlaying: Boolean = true,
    beatHaptics: Boolean? = null, // null = not offered (edition without the wave, or no audio access)
    onToggleBeatHaptics: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 16.dp)
                .width(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C1C))
        ) {
            if (showNowPlaying) {
                Text(
                    "Now playing (other apps)",
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNowPlaying)
                        .padding(16.dp),
                )
            }
            Text(
                "Choose file",
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onChooseFile)
                    .padding(16.dp),
            )
            if (beatHaptics != null) {
                Text(
                    if (beatHaptics) "Beat haptics: on" else "Beat haptics: off",
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleBeatHaptics)
                        .padding(16.dp),
                )
            }
            Text(
                "Playlists",
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlaylists)
                    .padding(16.dp),
            )
        }
    }
}
