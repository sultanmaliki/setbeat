package dev.fitnesstimer.nowplaying

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Picks out beats (kick drums and other bass hits) from the raw low-band level of the audio capture,
 * for the beat haptics. Pure Kotlin so it is unit-tested; fed one raw low-band magnitude per capture
 * frame (about 20 per second) from [AudioLevelSource].
 *
 * It looks at how much the bass *jumps* from one frame to the next, not at its level: real music has a
 * busy, rarely quiet bass line, so a level threshold either misses whole stretches (measured on a real
 * track: gaps of 7 s) or never settles. A frame is a beat when its jump is clearly bigger than the
 * recent jumps (mean plus [k] standard deviations over the last [window] frames, never below
 * [minRise]), the bass is not near-silence, and it comes at least [minIntervalMs] after the previous
 * beat, so a single hit never double-fires. Tuned on a captured 50 s bass trace; see DECISIONS.md.
 */
class BeatDetector(
    private val window: Int = 24,
    private val k: Float = 0.7f,
    private val minRise: Float = 8f,
    private val minIntervalMs: Long = 280L,
    private val minMagnitude: Float = 8f,
    private val warmupFrames: Int = 12,
) {
    private val rises = FloatArray(window)
    private var next = 0
    private var frames = 0
    private var prev = 0f
    private var lastBeatMs = Long.MIN_VALUE / 2

    /** Returns the beat strength in 0.25..1 when this frame is a beat, or 0 when it is not. */
    fun onFrame(timeMs: Long, low: Float): Float {
        val rise = if (frames == 0) 0f else max(0f, low - prev)
        var strength = 0f
        if (frames >= warmupFrames && low >= minMagnitude) {
            val n = minOf(frames, window)
            var sum = 0f
            for (i in 0 until n) sum += rises[i]
            val mean = sum / n
            var sq = 0f
            for (i in 0 until n) sq += (rises[i] - mean) * (rises[i] - mean)
            val threshold = max(mean + k * sqrt(sq / n), minRise)
            if (rise > threshold && timeMs - lastBeatMs >= minIntervalMs) {
                lastBeatMs = timeMs
                strength = (rise / threshold - 1f).coerceIn(0.25f, 1f)
            }
        }
        rises[next] = rise
        next = (next + 1) % window
        prev = low
        frames++
        return strength
    }
}
