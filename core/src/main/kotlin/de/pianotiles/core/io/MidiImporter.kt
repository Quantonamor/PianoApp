package de.pianotiles.core.io

import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongUtils

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal Standard MIDI File (SMF) reader (format 0 and 1).
 *
 * Times are converted to beats via the file's ticks-per-quarter resolution, so tempo changes
 * do not affect note positions. The first tempo event determines the song's base tempo.
 */
object MidiImporter {

    private class RawNote(val track: Int, val channel: Int, val pitch: Int, val startTick: Long, var endTick: Long, val velocity: Int)

    fun import(bytes: ByteArray, fallbackTitle: String): Song {
        val r = Reader(bytes)
        if (r.ascii(4) != "MThd") throw ImportException(t("Keine gültige MIDI-Datei (MThd fehlt)", "Not a valid MIDI file (MThd missing)"))
        val headerLen = r.u32()
        r.u16() // format (0 or 1, both handled the same way)
        val trackCount = r.u16()
        val division = r.u16()
        r.skip(headerLen - 6)
        if (division and 0x8000 != 0) throw ImportException("SMPTE-Zeitbasis wird nicht unterstützt")
        val tpq = division.toDouble().coerceAtLeast(1.0)

        var tempoMicros = -1L
        val tempoEvents = ArrayList<Pair<Long, Long>>()
        var tsNum = 4
        var tsDen = 4
        var keyFifths = 0
        var title: String? = null
        val notes = ArrayList<RawNote>()
        val trackNames = HashMap<Int, String>()

        for (t in 0 until trackCount) {
            if (r.remaining() < 8) break
            val id = r.ascii(4)
            val len = r.u32().toInt()
            if (id != "MTrk") { r.skip(len.toLong()); continue }
            val end = r.pos + len
            var tick = 0L
            var runningStatus = 0
            val open = HashMap<Int, ArrayDeque<RawNote>>() // key = channel*128+pitch
            while (r.pos < end) {
                tick += r.vlq()
                var status = r.u8()
                if (status < 0x80) { // running status
                    r.pos--
                    status = runningStatus
                } else if (status < 0xF0) {
                    runningStatus = status
                }
                when {
                    status == 0xFF -> {
                        val type = r.u8()
                        val l = r.vlq().toInt()
                        val data = r.bytes(l)
                        when (type) {
                            0x51 -> if (l >= 3) {
                                val us = ((data[0].toLong() and 0xFF) shl 16) or ((data[1].toLong() and 0xFF) shl 8) or (data[2].toLong() and 0xFF)
                                if (tempoMicros < 0) tempoMicros = us
                                if (us > 0) tempoEvents.add(tick to us)
                            }
                            0x58 -> if (l >= 2) { tsNum = data[0].toInt() and 0xFF; tsDen = 1 shl (data[1].toInt() and 0xFF) }
                            0x59 -> if (l >= 1) keyFifths = data[0].toInt()
                            0x03 -> {
                                val name = String(data, Charsets.ISO_8859_1).trim()
                                if (name.isNotEmpty()) {
                                    trackNames[t] = name
                                    if (title == null && t == 0) title = name
                                }
                            }
                        }
                    }
                    status == 0xF0 || status == 0xF7 -> r.skip(r.vlq())
                    else -> {
                        val type = status and 0xF0
                        val ch = status and 0x0F
                        val d1 = r.u8()
                        val d2 = if (type == 0xC0 || type == 0xD0) 0 else r.u8()
                        if (ch == 9) continue // percussion channel
                        val key = ch * 128 + d1
                        if (type == 0x90 && d2 > 0) {
                            val n = RawNote(t, ch, d1, tick, -1, d2)
                            open.getOrPut(key) { ArrayDeque() }.addLast(n)
                            notes.add(n)
                        } else if (type == 0x80 || (type == 0x90 && d2 == 0)) {
                            open[key]?.removeFirstOrNull()?.endTick = tick
                        }
                    }
                }
            }
            // close hanging notes
            open.values.forEach { q -> q.forEach { if (it.endTick < 0) it.endTick = tick } }
            r.pos = end
        }
        if (notes.isEmpty()) throw ImportException(t("Die MIDI-Datei enthält keine Noten", "The MIDI file contains no notes"))

        val hands = assignHands(notes)
        val result = notes.map {
            val start = it.startTick / tpq
            val dur = ((it.endTick - it.startTick) / tpq).coerceAtLeast(1.0 / 16)
            Note(it.pitch, start, dur, hands[it] ?: Hand.RIGHT, it.velocity)
        }
        val bpm = if (tempoMicros > 0) 60_000_000.0 / tempoMicros else 120.0
        val cleanTitle = title?.takeIf { it.length > 1 && !it.startsWith("Track", true) } ?: fallbackTitle
        val song = Song(
            id = SongUtils.newId(),
            title = cleanTitle,
            bpm = Math.round(bpm * 10) / 10.0,
            beatsPerMeasure = tsNum,
            beatUnit = tsDen,
            notes = SongUtils.dedupe(result),
            tempoChanges = tempoEvents.sortedBy { it.first }.filter { it.first > 0 }
                .map { de.pianotiles.core.model.TempoChange(it.first / tpq, Math.round(600_000_000.0 / it.second) / 10.0) }
                .distinctBy { it.beat },
            source = SongSource.MIDI,
            createdAt = System.currentTimeMillis(),
        )
        return song
    }

    /** Uses track (or channel) separation when there are exactly two voices, else splits at middle C. */
    private fun assignHands(notes: List<RawNote>): Map<RawNote, Hand> {
        val byTrack = notes.groupBy { it.track }
        val groups = if (byTrack.size >= 2) byTrack.values.toList() else notes.groupBy { it.channel }.values.toList()
        val result = HashMap<RawNote, Hand>()
        if (groups.size == 2) {
            val (a, b) = groups
            val aHigher = a.map { it.pitch }.average() >= b.map { it.pitch }.average()
            a.forEach { result[it] = if (aHigher) Hand.RIGHT else Hand.LEFT }
            b.forEach { result[it] = if (aHigher) Hand.LEFT else Hand.RIGHT }
        } else {
            notes.forEach { result[it] = if (it.pitch >= 60) Hand.RIGHT else Hand.LEFT }
        }
        return result
    }

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun remaining() = data.size - pos
        fun u8(): Int {
            if (pos >= data.size) throw ImportException("MIDI-Datei ist unvollständig")
            return data[pos++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun ascii(n: Int): String = String(bytes(n), Charsets.US_ASCII)
        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw ImportException("MIDI-Datei ist unvollständig")
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skip(n: Long) { pos = (pos + n.toInt()).coerceAtMost(data.size) }
        fun vlq(): Long {
            var v = 0L
            for (i in 0 until 4) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return v
            }
            return v
        }
    }
}
