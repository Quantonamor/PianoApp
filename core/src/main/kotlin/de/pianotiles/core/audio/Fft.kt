package de.pianotiles.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT with precomputed twiddle factors. */
class Fft(val size: Int) {
    init { require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two" } }

    private val cosTable = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2 * PI * it / size) }
    private val bitRev = IntArray(size).also { rev ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]; val wi = sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Hann-windowed magnitude spectrum of [samples] (length = size). Result has size/2 bins. */
    fun magnitudes(samples: FloatArray, out: DoubleArray = DoubleArray(size / 2)): DoubleArray {
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in 0 until size) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (size - 1))
            re[i] = samples[i] * w
        }
        transform(re, im)
        for (k in 0 until size / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * 2.0 / size
        return out
    }
}
