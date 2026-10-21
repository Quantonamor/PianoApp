package de.pianotiles.core

import de.pianotiles.core.audio.NoteDetector
import de.pianotiles.core.model.Pitch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorTest {
    private val sr = 44100

    /** Piano-like tone: decaying harmonics with slight inharmonicity. */
    private fun tone(pitches: List<Int>, seconds: Double, amp: Double = 0.2, noise: Double = 0.002): FloatArray {
        val n = (seconds * sr).toInt()
        val rnd = Random(1)
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var v = 0.0
            for (p in pitches) {
                val f0 = Pitch.frequency(p)
                for (h in 1..10) {
                    val f = f0 * h * (1 + 0.0004 * h * h)
                    if (f > sr / 2) break
                    val a = amp / pitches.size / h * (if (p < 45 && h == 1) 0.3 else 1.0)
                    v += a * sin(2 * PI * f * t) * exp(-t * (1.5 + h * 0.3))
                }
            }
            (v + noise * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private fun detect(pitches: List<Int>): Set<Int> {
        val d = NoteDetector()
        val frames = d.process(tone(pitches, 0.6))
        // majority vote over the frames after the window filled
        val counts = HashMap<Int, Int>()
        frames.forEach { f -> f.sounding.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.filter { it.value >= frames.size / 2 }.keys
    }

    @Test
    fun singleNotes() {
        for (p in listOf(40, 48, 57, 60, 64, 69, 72, 79, 84, 91)) {
            val got = detect(listOf(p))
            assertTrue(p in got, "pitch $p -> $got")
            assertTrue(got.size <= 2, "pitch $p -> too many $got")
        }
    }

    @Test
    fun chord() {
        val got = detect(listOf(60, 64, 67))
        assertTrue(got.containsAll(listOf(60, 64, 67)), "got $got")
    }

    @Test
    fun silenceDetectsNothing() {
        val d = NoteDetector()
        val frames = d.process(FloatArray(sr) { ((Random.nextDouble() * 2 - 1) * 0.001).toFloat() })
        assertTrue(frames.all { it.sounding.isEmpty() }, frames.map { it.sounding }.toString())
    }

    @Test
    fun onsetOnRestrike() {
        val d = NoteDetector()
        val a = tone(listOf(60), 0.5)
        val f1 = d.process(a)
        assertTrue(f1.any { 60 in it.onsets })
        val f2 = d.process(tone(listOf(60), 0.5))
        assertTrue(f2.any { 60 in it.onsets }, "re-strike not detected")
        assertEquals(true, f2.last().sounding.contains(60))
    }
}
