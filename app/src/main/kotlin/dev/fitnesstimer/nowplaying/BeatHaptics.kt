package dev.fitnesstimer.nowplaying

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat

/**
 * A short, crisp vibration on each detected beat (Full edition, opt-in from the source menu).
 *
 * Needs the normal-level VIBRATE permission, which only the Full edition declares; without it, or on a
 * device with no vibrator, [pulse] does nothing. Strength scales the length and, where the motor
 * supports it, the amplitude; otherwise the system's own TICK effect is used. Consecutive pulses are
 * kept at least [MIN_GAP_MS] apart so a burst of detections can never turn into a buzz.
 */
object BeatHaptics {
    private const val MIN_GAP_MS = 120L

    private var vibrator: Vibrator? = null
    private var allowed = false
    private var lastPulseMs = 0L

    /** Idempotent. Returns whether a pulse can actually be felt on this phone with this build. */
    fun init(context: Context): Boolean {
        val app = context.applicationContext
        allowed = ContextCompat.checkSelfPermission(app, android.Manifest.permission.VIBRATE) ==
            PackageManager.PERMISSION_GRANTED
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return allowed && vibrator?.hasVibrator() == true
    }

    // The VIBRATE permission is checked in init(); lint cannot see that across the flavors.
    @SuppressLint("MissingPermission")
    fun pulse(strength: Float) {
        val v = vibrator ?: return
        if (!allowed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPulseMs < MIN_GAP_MS) return
        lastPulseMs = now
        val s = strength.coerceIn(0f, 1f)
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot((14 + 16 * s).toLong(), (110 + 145 * s).toInt().coerceIn(1, 255))
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            v.vibrate(effect)
        }
    }
}
