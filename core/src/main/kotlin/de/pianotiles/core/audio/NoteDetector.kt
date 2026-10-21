package de.pianotiles.core.audio

import de.pianotiles.core.model.Pitch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Result of analysing one audio frame. */
data class DetectionFrame(
    /** Pitches that are currently sounding. */
    val sounding: Set<Int>,
    /** Pitches that were (re-)struck in this frame. */
    val onsets: Set<Int>,
    /** RMS level 0..1 (for the input meter). */
    val level: Double,
    /** Salience of the strongest pitch, for debugging/tuning. */
    val peakSalience: Double,
)

/**
 * Polyphonic piano note detection based on harmonic salience:
 *
 * 1. Hann window + FFT (default 8192 samples @ 44.1 kHz ≈ 5.4 Hz resolution).
 * 2. For every piano key, the spectral peaks at its first harmonics are summed (weighted).
 * 3. The strongest key is picked, its harmonics are removed from the spectrum ("explaining away"),
 *    and the process repeats for up to [maxPolyphony] notes.
 * 4. An onset is reported for a key that newly appears or whose salience jumps up (re-struck key).
 *
 * Feed audio with [process]; it keeps an internal ring buffer and analyses every [hopSize] samples.
 */
class NoteDetector(
    val sampleRate: Int = 44100,
    val frameSize: Int = 8192,
    val hopSize: Int = 2048,
    var minPitch: Int = Pitch.LOWEST_PIANO,
    var maxPitch: Int = Pitch.HIGHEST_PIANO,
    /** 0..1 – higher means quieter notes are detected (but also more false positives). */
    var sensitivity: Double = 0.5,
    var maxPolyphony: Int = 6,
) {
    private val fft = Fft(frameSize)
    private val ring = FloatArray(frameSize)
    private var ringPos = 0
    private var filled = 0
    private var sinceHop = 0
    private val frame = FloatArray(frameSize)
    private val spectrum = DoubleArray(frameSize / 2)
    private val binHz = sampleRate.toDouble() / frameSize

    private var prevSounding: Set<Int> = emptySet()
    /** Recent salience values per key (ring buffer) for onset detection. */
    private val history = Array(128) { DoubleArray(HISTORY) }
    private var historyPos = 0
    /** Frames since the last onset per key (prevents double triggers while a note swells). */
    private val sinceOnset = IntArray(128) { 1000 }
    /** Running estimate of the background noise salience; negative = not initialised. */
    private var noiseFloor = -1.0
    /** Threshold used in the last frame (exposed for tuning/debug UI). */
    var lastThreshold = 0.0
        private set

    /** Process PCM samples in -1..1. Returns one result per analysed hop (may be empty). */
    fun process(samples: FloatArray, count: Int = samples.size): List<DetectionFrame> {
        val results = ArrayList<DetectionFrame>()
        for (i in 0 until count) {
            ring[ringPos] = samples[i]
            ringPos = (ringPos + 1) % frameSize
            if (filled < frameSize) filled++
            sinceHop++
            if (sinceHop >= hopSize && filled == frameSize) {
                sinceHop = 0
                for (j in 0 until frameSize) frame[j] = ring[(ringPos + j) % frameSize]
                results.add(analyze(frame))
            }
        }
        return results
    }

    fun reset() {
        ring.fill(0f); filled = 0; sinceHop = 0; prevSounding = emptySet(); noiseFloor = -1.0
        history.forEach { it.fill(0.0) }; sinceOnset.fill(1000)
    }

    fun analyze(samples: FloatArray): DetectionFrame {
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        val rms = sqrt(sumSq / samples.size)
        fft.magnitudes(samples, spectrum)
        // Light compression makes weak upper harmonics count more evenly.
        for (k in spectrum.indices) spectrum[k] = sqrt(spectrum[k])

        val sal = HashMap<Int, Double>()
        for (p in minPitch..maxPitch) sal[p] = salience(p)
        val maxSal = sal.values.maxOrNull() ?: 0.0

        // Noise floor = median spectral magnitude between 50 Hz and 5 kHz. Most bins are empty even
        // for chords, so the median reflects background noise, not the music.
        val lo = (50 / binHz).toInt()
        val hi = min(spectrum.size - 1, (5000 / binHz).toInt())
        val bins = spectrum.copyOfRange(lo, hi)
        bins.sort()
        val median = bins[bins.size / 2]
        noiseFloor = if (noiseFloor < 0 || median < noiseFloor) median else noiseFloor * 0.8 + median * 0.2
        val minRms = 0.004 * (1.5 - sensitivity)
        val threshold = max(noiseFloor * (9.0 - 6.0 * sensitivity), 0.01)
        lastThreshold = threshold
        val found = LinkedHashSet<Int>()
        val work = HashMap(sal)
        var first = 0.0
        if (rms >= minRms) repeat(maxPolyphony) {
            val best = work.maxByOrNull { it.value } ?: return@repeat
            if (best.value < threshold || (first > 0 && best.value < first * 0.33)) return@repeat
            if (first == 0.0) first = best.value
            found.add(best.key)
            removeHarmonics(best.key)
            // Recompute salience only for keys sharing harmonics (cheap enough to do all).
            for (p in minPitch..maxPitch) if (p !in found) work[p] = salience(p)
            work.remove(best.key)
            // Also suppress the octave-below ghost of the found note.
            work[best.key - 12]?.let { work[best.key - 12] = it * 0.5 }
        }

        val onsets = HashSet<Int>()
        for (p in found) {
            val minRecent = history[p].min()
            val rising = sal.getValue(p) > minRecent * 1.35
            if ((p !in prevSounding || rising) && sinceOnset[p] > 3) {
                onsets.add(p)
                sinceOnset[p] = 0
            }
        }
        for (p in minPitch..maxPitch) {
            history[p][historyPos] = sal.getValue(p)
            if (sinceOnset[p] < 1000) sinceOnset[p]++
        }
        historyPos = (historyPos + 1) % HISTORY
        prevSounding = found
        return DetectionFrame(found, onsets, rms, maxSal)
    }

    private companion object { const val HISTORY = 6 }

    private fun bandMax(freq: Double): Double {
        val c = freq / binHz
        val width = max(1.0, c * 0.025) // ~ ±0.4 semitones
        val lo = max(1, (c - width).toInt())
        val hi = min(spectrum.size - 1, (c + width).toInt() + 1)
        var m = 0.0
        for (k in lo..hi) if (spectrum[k] > m) m = spectrum[k]
        return m
    }

    private fun salience(pitch: Int): Double {
        val f0 = Pitch.frequency(pitch)
        var s = 0.0
        var h = 1
        while (h <= 8) {
            val f = f0 * h
            if (f > 5000 || f / binHz >= spectrum.size - 2) break
            s += bandMax(f) / h.toDouble().pow(0.6)
            h++
        }
        // Low notes have weak fundamentals; high notes have few harmonics. Normalise roughly.
        return s / (h - 1).coerceAtLeast(1).toDouble().pow(0.5)
    }

    private fun removeHarmonics(pitch: Int) {
        val f0 = Pitch.frequency(pitch)
        for (h in 1..8) {
            val f = f0 * h
            if (f > 5000) break
            val c = f / binHz
            val width = max(1.5, c * 0.03)
            val lo = max(1, (c - width).toInt())
            val hi = min(spectrum.size - 1, (c + width).toInt() + 1)
            for (k in lo..hi) spectrum[k] *= 0.15
        }
    }
}
