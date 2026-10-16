package dev.fitnesstimer.nowplaying

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "FitnessTimer"

/**
 * Whether a Bluetooth output (classic A2DP or BLE audio) is currently connected. Used only to
 * pick accurate wording for [Status.SILENT] ("wave isn't reacting") — never to gate the capture
 * itself, since plenty of non-Bluetooth playback hits the same hardware-offload wall (confirmed
 * on-device 2026-09-22; see DECISIONS.md). `AudioManager.getDevices()` needs no permission (no
 * `@RequiresPermission` in the AOSP source, cross-checked directly against
 * `frameworks/base/media/java/android/media/AudioManager.java`) and has existed since API 23, well
 * under this app's minSdk 29, so no version guard is needed for the older BLE type constants
 * either (comparing against a constant a device can't report is harmless, just never matches).
 */
fun isBluetoothAudioOutputActive(context: Context): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            d.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            d.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
    }
}

/**
 * Real-time low/mid/high levels of whatever the phone is currently playing,
 * for the beat-reactive wave (PLAN.md N.5e).
 *
 * Uses android.media.audiofx.Visualizer. It starts on audio session 0 (the
 * output mix), the only unprivileged way to see another app's audio without
 * knowing its session. It needs the RECORD_AUDIO permission (the Visualizer
 * is documented as gated on it) plus MODIFY_AUDIO_SETTINGS, and is documented
 * as partial/low-quality data — fine for a visualization, and nothing is
 * recorded, stored or sent anywhere.
 *
 * Session 0 returns only zeros for audio that bypasses the normal output mix:
 * hardware offload, and (confirmed on a POCO X7 Pro, 2026-10-13) tracks routed
 * through the system spatializer, e.g. Xiaomi's "Immersive sound". In that case
 * [discoverTrackSession] looks for the playing track's own audio session: it
 * tries recent session ids, which only succeeds for sessions that currently
 * exist, and attaches to the one that carries real audio. Only magnitudes are
 * read, and only while the companion screen is visible and playing.
 *
 * It only runs while (a) the UI wants it, (b) the Activity is visible and
 * (c) the permission is granted, so it costs nothing in the background.
 * Whether any of this delivers data on a given Android version/ROM is
 * device-dependent: [status] reports ACTIVE / SILENT / FAILED honestly and
 * the UI falls back to a quiet idle wave rather than faking motion.
 */
object AudioLevelSource {
    enum class Status { STOPPED, ACTIVE, SILENT, FAILED }

    /** How many session ids below the newest to try. A live track's id can be old; this is generous. */
    private const val SCAN_RANGE = 6000
    private const val SCAN_BATCH = 16
    private const val SCAN_SETTLE_MS = 45L
    private const val RESCAN_INTERVAL_MS = 10_000L
    private const val SILENT_CALLBACKS_BEFORE_FALLBACK = 60

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status

    private val _permission = MutableStateFlow(false)
    val permission: StateFlow<Boolean> = _permission

    /** Latest smoothed low/mid/high levels, 0..1. Written from the capture thread. */
    @Volatile
    var levels: FloatArray = FloatArray(BAND_COUNT)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var visualizer: Visualizer? = null
    @Volatile private var session = 0
    private var wanted = false
    private var visible = false
    private var normalizer = BandNormalizer()
    private var smoother = BandSmoother()
    private var beatDetector = BeatDetector()

    /** Called from the capture thread with the beat strength (0.25..1) on each detected beat; null = off. */
    @Volatile
    var onBeat: ((Float) -> Unit)? = null
    private var silentCallbacks = 0
    private var callbacks = 0
    @Volatile private var discovering = false
    private var lastDiscoveryMs = 0L

    fun refreshPermission(context: Context) {
        appContext = context.applicationContext
        _permission.value = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        apply()
    }

    fun setWanted(value: Boolean) {
        wanted = value
        apply()
    }

    fun setVisible(value: Boolean) {
        visible = value
        apply()
    }

    private fun apply() {
        val shouldRun = wanted && visible && _permission.value
        if (shouldRun && visualizer == null) start(0) else if (!shouldRun && visualizer != null) stop()
    }

    private fun start(sessionId: Int) {
        try {
            val v = Visualizer(sessionId)
            val sizeRange = Visualizer.getCaptureSizeRange()
            v.captureSize = sizeRange[1].coerceAtMost(1024)
            normalizer = BandNormalizer()
            smoother = BandSmoother()
            beatDetector = BeatDetector()
            silentCallbacks = 0
            callbacks = 0
            val rate = Visualizer.getMaxCaptureRate().coerceAtLeast(1)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null) return
                        onFft(fft, samplingRate / 1000)
                    }
                },
                rate,
                /* waveform = */ false,
                /* fft = */ true,
            )
            v.enabled = true
            visualizer = v
            session = sessionId
            Log.d(TAG, "[viz] started session=$sessionId captureSize=${v.captureSize} rate=${rate}mHz samplingRate=${v.samplingRate}mHz")
        } catch (e: Exception) {
            Log.w(TAG, "[viz] FAILED to start session=$sessionId: ${e.javaClass.simpleName}: ${e.message}")
            visualizer = null
            _status.value = Status.FAILED
        }
    }

    private fun onFft(fft: ByteArray, samplingRateHz: Int) {
        callbacks++
        val peak = peakMagnitude(fft)
        if (peak <= 0f) {
            silentCallbacks++
            if (silentCallbacks >= SILENT_CALLBACKS_BEFORE_FALLBACK) {
                if (session == 0 && _status.value != Status.SILENT) {
                    _status.value = Status.SILENT
                    Log.w(TAG, "[viz] SILENT: session 0 returns only zeros (offload, spatializer, or nothing playing)")
                }
                // Keep looking for the track's own session (rate-limited), whether we are on
                // session 0 or on a track session that has since gone quiet (track changed/ended).
                main.post { maybeDiscover() }
            }
        } else {
            silentCallbacks = 0
            if (_status.value != Status.ACTIVE) {
                _status.value = Status.ACTIVE
                Log.d(TAG, "[viz] ACTIVE: first non-zero data session=$session peak=$peak samplingRate=${samplingRateHz}Hz size=${fft.size}")
            }
        }
        val raw = computeBandMagnitudes(fft, samplingRateHz)
        val bands = smoother.smooth(normalizer.normalize(raw))
        val beat = beatDetector.onFrame(SystemClock.uptimeMillis(), raw[BAND_LOW])
        if (beat > 0f) {
            Log.d(TAG, "[viz] beat strength=${"%.2f".format(beat)} low=${"%.0f".format(raw[BAND_LOW])}")
            onBeat?.invoke(beat)
        }
        levels = bands
        if (callbacks % 40 == 1) {
            Log.d(TAG, "[viz] session=$session peak=${"%.1f".format(peak)} low=${"%.2f".format(bands[0])} mid=${"%.2f".format(bands[1])} high=${"%.2f".format(bands[2])}")
        }
    }

    /** Main thread. Starts at most one background scan per [RESCAN_INTERVAL_MS]. */
    private fun maybeDiscover() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (visualizer == null || discovering || now - lastDiscoveryMs < RESCAN_INTERVAL_MS) return
        discovering = true
        lastDiscoveryMs = now
        Thread({
            val found = try {
                discoverTrackSession(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "[viz] session scan failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            main.post {
                discovering = false
                if (found != null && visualizer != null && found != session) {
                    Log.d(TAG, "[viz] switching to track session=$found (was $session)")
                    releaseVisualizer()
                    start(found)
                } else if (found == null && session != 0 && visualizer != null) {
                    // The track session we were on is gone and nothing else carries audio: go back
                    // to the output mix so ordinary playback is still picked up.
                    releaseVisualizer()
                    start(0)
                }
            }
        }, "viz-session-scan").start()
    }

    /**
     * Tries session ids from the newest downwards. Constructing a [Visualizer] on an id that no
     * longer exists fails fast, so the cost is bounded by the number of live sessions. Returns the
     * live session whose audio has the highest magnitude right now, or null if none carries audio.
     * Reads magnitudes only; nothing is kept.
     */
    private fun discoverTrackSession(context: Context): Int? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val top = am.generateAudioSessionId()
        val captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
        var bestId: Int? = null
        var bestPeak = 0f
        var id = top - 1
        val floor = (top - SCAN_RANGE).coerceAtLeast(1)
        while (id >= floor) {
            val batch = ArrayList<Pair<Int, Visualizer>>(SCAN_BATCH)
            while (id >= floor && batch.size < SCAN_BATCH) {
                if (id != session) {
                    try {
                        val v = Visualizer(id)
                        v.captureSize = captureSize
                        v.enabled = true
                        batch += id to v
                    } catch (_: Exception) {
                        // No such live session (or the engine refused it): expected for most ids.
                    }
                }
                id--
            }
            if (batch.isNotEmpty()) {
                Thread.sleep(SCAN_SETTLE_MS)
                val buf = ByteArray(captureSize)
                for ((sid, v) in batch) {
                    try {
                        if (v.getFft(buf) == Visualizer.SUCCESS) {
                            val p = peakMagnitude(buf)
                            if (p > bestPeak) {
                                bestPeak = p
                                bestId = sid
                            }
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        v.enabled = false
                        v.release()
                    } catch (_: Exception) {
                    }
                }
                // Newest sessions first: once a batch has real audio, that is the playing track.
                if (bestId != null) break
            }
        }
        Log.d(TAG, "[viz] session scan from $top: best=${bestId ?: "none"} peak=$bestPeak")
        return bestId
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "[viz] error while releasing", e)
        }
        visualizer = null
        session = 0
    }

    private fun stop() {
        releaseVisualizer()
        levels = FloatArray(BAND_COUNT)
        _status.value = Status.STOPPED
        Log.d(TAG, "[viz] stopped")
    }
}
