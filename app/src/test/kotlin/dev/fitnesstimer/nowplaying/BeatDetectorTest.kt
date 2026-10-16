package dev.fitnesstimer.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {
    private val frameMs = 50L

    /** A kick every [beatMs]: a short burst on a steady bass floor, sampled every [frameMs]. */
    private fun kickTrain(seconds: Int, beatMs: Long, floor: Float = 15f, hit: Float = 70f): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>()
        var t = 0L
        while (t < seconds * 1000L) {
            val sinceBeat = t % beatMs
            val v = when {
                sinceBeat < frameMs -> hit * 0.6f // the attack frame is partway up
                sinceBeat < 2 * frameMs -> hit
                sinceBeat < 3 * frameMs -> hit * 0.5f
                else -> floor
            }
            out += t to v
            t += frameMs
        }
        return out
    }

    private fun run(frames: List<Pair<Long, Float>>): List<Long> {
        val d = BeatDetector()
        return frames.filter { (t, v) -> d.onFrame(t, v) > 0f }.map { it.first }
    }

    @Test
    fun `finds one beat per kick at 120 bpm`() {
        val beats = run(kickTrain(seconds = 20, beatMs = 500))
        // 40 kicks in 20 s; the first few are swallowed by the warm-up.
        assertTrue("got ${beats.size}", beats.size in 34..40)
    }

    @Test
    fun `beats are never closer than the minimum interval`() {
        val beats = run(kickTrain(seconds = 20, beatMs = 300))
        assertTrue(beats.zipWithNext().all { (a, b) -> b - a >= 260 })
    }

    @Test
    fun `silence and near silence never fire`() {
        assertEquals(0, run((0 until 400).map { it * frameMs to 0f }).size)
        assertEquals(0, run((0 until 400).map { it * frameMs to 3f }).size)
    }

    @Test
    fun `a steady loud bass line does not keep firing`() {
        val beats = run((0 until 400).map { it * frameMs to 60f })
        assertEquals(0, beats.size)
    }

    @Test
    fun `stronger hits report stronger beats`() {
        val weak = BeatDetector()
        val strong = BeatDetector()
        for (i in 0 until 12) {
            weak.onFrame(i * frameMs, 15f)
            strong.onFrame(i * frameMs, 15f)
        }
        val w = weak.onFrame(12 * frameMs, 25f)
        val s = strong.onFrame(12 * frameMs, 80f)
        assertTrue("weak=$w strong=$s", w > 0f && s > w)
        assertTrue(s <= 1f && w >= 0.25f)
    }
}
