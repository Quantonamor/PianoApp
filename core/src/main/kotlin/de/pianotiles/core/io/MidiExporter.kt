package de.pianotiles.core.io

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Song
import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/** Writes a song as Standard MIDI File (format 1: tempo track, right hand, left hand). */
object MidiExporter {
    private const val TPQ = 480

    fun export(song: Song): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(int32(6)); out.write(int16(1)); out.write(int16(3)); out.write(int16(TPQ))
        // tempo / meta track
        val meta = ArrayList<Pair<Long, ByteArray>>()
        meta.add(0L to byteArrayOf(0xFF.toByte(), 0x03) + vlqBytes(song.title.toByteArray().size) + song.title.toByteArray())
        meta.add(0L to tempoEvent(song.bpm))
        val den = Integer.numberOfTrailingZeros(song.beatUnit)
        meta.add(0L to byteArrayOf(0xFF.toByte(), 0x58, 4, song.beatsPerMeasure.toByte(), den.toByte(), 24, 8))
        song.tempoChanges.forEach { meta.add(ticks(it.beat) to tempoEvent(it.bpm)) }
        out.write(track(meta))
        for ((ch, hand) in listOf(0 to Hand.RIGHT, 1 to Hand.LEFT)) {
            val ev = ArrayList<Pair<Long, ByteArray>>()
            for (n in song.notes.filter { it.hand == hand }) {
                ev.add(ticks(n.start) to byteArrayOf((0x90 or ch).toByte(), n.pitch.toByte(), n.velocity.coerceIn(1, 127).toByte()))
                ev.add(ticks(n.end) to byteArrayOf((0x80 or ch).toByte(), n.pitch.toByte(), 0))
            }
            out.write(track(ev))
        }
        return out.toByteArray()
    }

    private fun ticks(beat: Double) = (beat * TPQ).roundToLong().coerceAtLeast(0)
    private fun tempoEvent(bpm: Double): ByteArray {
        val us = (60_000_000 / bpm).toInt()
        return byteArrayOf(0xFF.toByte(), 0x51, 3, (us shr 16).toByte(), (us shr 8).toByte(), us.toByte())
    }

    private fun track(events: List<Pair<Long, ByteArray>>): ByteArray {
        // note-offs before note-ons at the same tick
        val sorted = events.sortedWith(compareBy({ it.first }, { if ((it.second[0].toInt() and 0xF0) == 0x80) 0 else 1 }))
        val body = ByteArrayOutputStream()
        var last = 0L
        for ((t, e) in sorted) { body.write(vlqBytes((t - last).toInt())); body.write(e); last = t }
        body.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
        val b = body.toByteArray()
        return "MTrk".toByteArray() + int32(b.size) + b
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun vlqBytes(value: Int): ByteArray {
        var v = value
        val bytes = ArrayList<Int>()
        bytes.add(v and 0x7F); v = v shr 7
        while (v > 0) { bytes.add((v and 0x7F) or 0x80); v = v shr 7 }
        return bytes.reversed().map { it.toByte() }.toByteArray()
    }
}
