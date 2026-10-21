package de.pianotiles.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import de.pianotiles.core.audio.DetectionFrame
import de.pianotiles.core.audio.NoteDetector

/**
 * Reads the microphone on a background thread and runs [NoteDetector] on it.
 * Results are delivered on the main thread via [onFrame].
 */
class MicInput(private val onFrame: (DetectionFrame) -> Unit) {
    private val sampleRate = 44100
    private val detector = NoteDetector(sampleRate = sampleRate)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    var sensitivity: Double
        get() = detector.sensitivity
        set(value) { detector.sensitivity = value }

    val isRunning get() = running

    /** Requires the RECORD_AUDIO permission. Returns false when the microphone cannot be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        } catch (e: Exception) { return false }
        val record = if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            rec.release()
            try {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
            } catch (e: Exception) { return false }
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        running = true
        detector.reset()
        thread = Thread({
            val buf = ShortArray(1024)
            val f = FloatArray(1024)
            try {
                runCatching { record.startRecording() }.onFailure { running = false; return@Thread }
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) f[i] = buf[i] / 32768f
                    for (frame in detector.process(f, n)) main.post { if (running) onFrame(frame) }
                }
            } catch (e: Exception) {
                android.util.Log.w("MicInput", "recording failed", e)
                running = false
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "MicInput").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
