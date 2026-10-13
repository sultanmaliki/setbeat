package dev.fitnesstimer

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.fitnesstimer.nowplaying.AudioLevelSource
import dev.fitnesstimer.nowplaying.NowPlayingRepository
import dev.fitnesstimer.nowplaying.positionNow
import dev.fitnesstimer.timer.AppTimer
import dev.fitnesstimer.timer.CountdownAlarm
import dev.fitnesstimer.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val companionEnabled by lazy { resources.getBoolean(R.bool.companion_enabled) }
    // Vibes edition (DECISIONS.md): this edition has no RECORD_AUDIO permission
    // at all, so the real audio capture must never be touched, not just left unused.
    private val fakeWave by lazy { resources.getBoolean(R.bool.fake_wave) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTimer.init(this)
        if (companionEnabled) NowPlayingRepository.init(this)
        enableEdgeToEdge()
        // Debug-only hook: `adb shell am start -n dev.fitnesstimer/.MainActivity
        // --es debug_media_uri file:///...` loads a file without the SAF picker.
        // Exists because the test phone's MIUI blocks scripted taps, so the
        // picker can't be driven from adb — this lets the playback pipeline
        // be tested independently of it. Harmless when the extra is absent.
        // Only honoured in debuggable builds: the activity is exported, so in
        // a release build any other app could otherwise inject a URI here.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // savedInstanceState == null: a recreation must not restart the queue.
        val debugUris = if (debuggable && savedInstanceState == null) {
            intent.getStringExtra("debug_media_uris")
                ?.split(",")?.filter { it.isNotBlank() }?.map { Uri.parse(it) }
                ?: emptyList()
        } else emptyList()
        // A gym timer that sleeps mid-set is useless: keep the screen on
        // while this Activity is visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (debuggable && intent.getBooleanExtra("debug_timer_start", false) && savedInstanceState == null) {
            AppTimer.engine.start()
        }
        if (savedInstanceState == null) handleDebugAction(intent)
        setContent {
            MainScreen(debugUris = debugUris)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugAction(intent)
    }

    // Debuggable builds only: `--es debug_np_action playpause|next|prev|seek+|seek-` drives the same
    // NowPlayingRepository calls the gestures use (MIUI blocks scripted touches).
    private fun handleDebugAction(intent: android.content.Intent) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        // `--el debug_countdown_ms N`: start an N ms countdown (for alarm testing).
        val countdownMs = intent.getLongExtra("debug_countdown_ms", -1L)
        if (countdownMs > 0) {
            AppTimer.engine.switchToCountdown(countdownMs)
            AppTimer.engine.start()
        }
        val np = NowPlayingRepository.state.value.nowPlaying
        val action = intent.getStringExtra("debug_np_action") ?: return
        android.util.Log.d("FitnessTimer", "[debug] np action=$action np=${np?.packageName} state=${np?.playbackState}")
        when (action) {
            "playpause" -> NowPlayingRepository.playPause()
            "next" -> NowPlayingRepository.skipNext()
            "prev" -> NowPlayingRepository.skipPrevious()
            "seek+" -> np?.let { NowPlayingRepository.seekTo(it.positionNow() + 10_000L) }
            "seek-" -> np?.let { NowPlayingRepository.seekTo((it.positionNow() - 10_000L).coerceAtLeast(0L)) }
        }
    }

    override fun onPause() {
        AppTimer.uiVisible = false
        if (companionEnabled && !fakeWave) AudioLevelSource.setVisible(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        AppTimer.uiVisible = true
        if (companionEnabled && !fakeWave) {
            AudioLevelSource.refreshPermission(this)
            AudioLevelSource.setVisible(true)
        }
        CountdownAlarm.cancelFinishedNotification(this)
        // The user may have just toggled notification access in Settings.
        if (companionEnabled) NowPlayingRepository.refresh()
    }
}
