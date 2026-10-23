package de.pianotiles.core.io

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes a song as MusicXML 4.0 (score-partwise): one piano part with two staves (right hand = treble staff,
 * left hand = bass staff). This is the "Noten-Datei" of a song package and can be opened in MuseScore,
 * Finale, Sibelius, Dorico, … Fingerings are written as `<fingering>`, tempo changes as `<sound tempo>`.
 *
 * Times are quantised to 1/48 of a quarter note (covers 64ths and triplets). Overlapping notes of one hand
 * go into separate voices; notes crossing a bar line are split and tied.
 */
object MusicXmlExporter {
    const val DIVISIONS = 48

    fun export(song: Song): String {
        val key = song.keyFifths ?: KeyGuess.fifths(song.notes)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""").append('\n')
        sb.append("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""").append('\n')
        sb.append("""<score-partwise version="4.0">""").append('\n')
        sb.append("  <work><work-title>").append(esc(song.title)).append("</work-title></work>\n")
        sb.append("  <identification>\n")
        if (song.composer.isNotBlank()) sb.append("    <creator type=\"composer\">").append(esc(song.composer)).append("</creator>\n")
        sb.append("    <encoding><software>PianoTiles</software></encoding>\n")
        sb.append("  </identification>\n")
        sb.append("  <part-list><score-part id=\"P1\"><part-name>Piano</part-name></score-part></part-list>\n")
        sb.append("  <part id=\"P1\">\n")

        val count = song.measureCount
        val bounds = (0..count).map { q(song.measureStart(it)) }
        val pieces = HashMap<Pair<Int, Hand>, MutableList<Piece>>()
        for (n in song.notes) {
            var s = q(n.start)
            val e = maxOf(q(n.end), s + 3)
            var first = true
            while (s < e) {
                val m = measureOfTick(bounds, s).coerceAtMost(count - 1)
                val end = if (m == count - 1) e else minOf(e, bounds[m + 1])
                pieces.getOrPut(m to n.hand) { ArrayList() }
                    .add(Piece(n.pitch, s - bounds[m], end - s, if (first) n.finger else 0, tieStop = !first, tieStart = end < e))
                first = false
                s = end
            }
        }
        val tempoTicks = song.tempoChanges.map { q(it.beat) to it.bpm }

        for (m in 0 until count) {
            val len = bounds[m + 1] - bounds[m]
            val implicit = m == 0 && song.pickupBeats > 0
            val number = if (song.pickupBeats > 0) m else m + 1
            sb.append("    <measure number=\"").append(number).append('"')
            if (implicit) sb.append(" implicit=\"yes\"")
            sb.append(">\n")
            if (m == 0) {
                sb.append("      <attributes>\n")
                sb.append("        <divisions>").append(DIVISIONS).append("</divisions>\n")
                sb.append("        <key><fifths>").append(key).append("</fifths></key>\n")
                sb.append("        <time><beats>").append(song.beatsPerMeasure).append("</beats><beat-type>").append(song.beatUnit).append("</beat-type></time>\n")
                sb.append("        <staves>2</staves>\n")
                sb.append("        <clef number=\"1\"><sign>G</sign><line>2</line></clef>\n")
                sb.append("        <clef number=\"2\"><sign>F</sign><line>4</line></clef>\n")
                sb.append("      </attributes>\n")
                tempo(sb, song.bpm, 0)
            }
            for ((tick, bpm) in tempoTicks) {
                if (tick <= 0 || measureOfTick(bounds, tick) != m) continue
                tempo(sb, bpm, tick - bounds[m])
            }
            var first = true
            for (hand in listOf(Hand.RIGHT, Hand.LEFT)) {
                val staff = if (hand == Hand.RIGHT) 1 else 2
                if (!first) sb.append("      <backup><duration>").append(len).append("</duration></backup>\n")
                first = false
                writeStaff(sb, pieces[m to hand].orEmpty(), len, staff, key)
            }
            if (m == count - 1) sb.append("      <barline location=\"right\"><bar-style>light-heavy</bar-style></barline>\n")
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n</score-partwise>\n")
        return sb.toString()
    }

    private class Piece(val pitch: Int, val start: Int, val dur: Int, val finger: Int, val tieStop: Boolean, val tieStart: Boolean)

    private fun writeStaff(sb: StringBuilder, pieces: List<Piece>, len: Int, staff: Int, key: Int) {
        val voiceBase = if (staff == 1) 1 else 5
        if (pieces.isEmpty()) {
            sb.append("      <note><rest measure=\"yes\"/><duration>").append(len).append("</duration><voice>").append(voiceBase)
                .append("</voice><staff>").append(staff).append("</staff></note>\n")
            return
        }
        // chords = same start and duration; distribute them over voices without overlaps
        val chords = pieces.groupBy { it.start to it.dur }.values.sortedWith(compareBy({ it[0].start }, { -it[0].dur }))
        val lanes = ArrayList<MutableList<List<Piece>>>()
        for (c in chords) {
            val lane = lanes.firstOrNull { l -> l.last()[0].let { it.start + it.dur } <= c[0].start }
            if (lane != null) lane.add(c) else lanes.add(arrayListOf(c))
        }
        val accidentals = Accidentals(key)
        for ((li, lane) in lanes.withIndex()) {
            if (li > 0) sb.append("      <backup><duration>").append(len).append("</duration></backup>\n")
            val voice = voiceBase + li
            var cursor = 0
            for (chord in lane) {
                val c0 = chord[0]
                if (c0.start > cursor) gap(sb, c0.start - cursor, voice, staff, rests = li == 0)
                val parts = split(c0.dur)
                for ((pi, part) in parts.withIndex()) {
                    for ((ni, p) in chord.sortedBy { it.pitch }.withIndex()) {
                        val tieStop = pi > 0 || p.tieStop
                        val tieStart = pi < parts.lastIndex || p.tieStart
                        note(sb, p.pitch, key, part, ni > 0, voice, staff, tieStop, tieStart,
                            if (pi == 0) p.finger else 0, if (pi == 0) accidentals.mark(p.pitch) else null)
                    }
                }
                cursor = c0.start + c0.dur
            }
            // every lane ends at the bar line, so the following <backup> is exact
            if (cursor < len) gap(sb, len - cursor, voice, staff, rests = li == 0)
        }
    }

    private fun gap(sb: StringBuilder, dur: Int, voice: Int, staff: Int, rests: Boolean) {
        if (!rests) {
            sb.append("      <forward><duration>").append(dur).append("</duration><voice>").append(voice).append("</voice><staff>").append(staff).append("</staff></forward>\n")
            return
        }
        for (v in split(dur)) {
            sb.append("      <note><rest/><duration>").append(v.ticks).append("</duration><voice>").append(voice).append("</voice>")
            typeTags(sb, v)
            sb.append("<staff>").append(staff).append("</staff></note>\n")
        }
    }

    private fun note(
        sb: StringBuilder, pitch: Int, key: Int, v: NoteValue, chord: Boolean, voice: Int, staff: Int,
        tieStop: Boolean, tieStart: Boolean, finger: Int, accidental: String?,
    ) {
        val (step, alter, octave) = spell(pitch, key)
        sb.append("      <note>")
        if (chord) sb.append("<chord/>")
        sb.append("<pitch><step>").append(step).append("</step>")
        if (alter != 0) sb.append("<alter>").append(alter).append("</alter>")
        sb.append("<octave>").append(octave).append("</octave></pitch>")
        sb.append("<duration>").append(v.ticks).append("</duration>")
        if (tieStop) sb.append("<tie type=\"stop\"/>")
        if (tieStart) sb.append("<tie type=\"start\"/>")
        sb.append("<voice>").append(voice).append("</voice>")
        sb.append("<type>").append(v.type).append("</type>")
        repeat(v.dots) { sb.append("<dot/>") }
        if (accidental != null) sb.append("<accidental>").append(accidental).append("</accidental>")
        if (v.triplet) sb.append("<time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")
        sb.append("<staff>").append(staff).append("</staff>")
        if (tieStop || tieStart || finger in 1..5) {
            sb.append("<notations>")
            if (tieStop) sb.append("<tied type=\"stop\"/>")
            if (tieStart) sb.append("<tied type=\"start\"/>")
            if (finger in 1..5) sb.append("<technical><fingering>").append(finger).append("</fingering></technical>")
            sb.append("</notations>")
        }
        sb.append("</note>\n")
    }

    private fun typeTags(sb: StringBuilder, v: NoteValue) {
        sb.append("<type>").append(v.type).append("</type>")
        repeat(v.dots) { sb.append("<dot/>") }
        if (v.triplet) sb.append("<time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")
    }

    private fun tempo(sb: StringBuilder, bpm: Double, offset: Int) {
        val t = (bpm * 100).roundToInt() / 100.0
        val text = if (t == t.toInt().toDouble()) t.toInt().toString() else t.toString()
        if (offset > 0) sb.append("      <forward><duration>").append(offset).append("</duration></forward>\n")
        sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>")
            .append(text).append("</per-minute></metronome></direction-type><staff>1</staff><sound tempo=\"").append(text).append("\"/></direction>\n")
        if (offset > 0) sb.append("      <backup><duration>").append(offset).append("</duration></backup>\n")
    }

    class NoteValue(val ticks: Int, val type: String, val dots: Int = 0, val triplet: Boolean = false)

    private val VALUES = listOf(
        NoteValue(288, "whole", 1), NoteValue(192, "whole"), NoteValue(144, "half", 1), NoteValue(96, "half"),
        NoteValue(72, "quarter", 1), NoteValue(64, "half", triplet = true), NoteValue(48, "quarter"),
        NoteValue(36, "eighth", 1), NoteValue(32, "quarter", triplet = true), NoteValue(24, "eighth"),
        NoteValue(18, "16th", 1), NoteValue(16, "eighth", triplet = true), NoteValue(12, "16th"),
        NoteValue(9, "32nd", 1), NoteValue(8, "16th", triplet = true), NoteValue(6, "32nd"), NoteValue(3, "64th"),
    )

    /** Splits a duration (in divisions) into printable note values; a tiny rest goes to the last value. */
    internal fun split(ticks: Int): List<NoteValue> {
        val out = ArrayList<NoteValue>()
        var rest = ticks
        while (rest >= 3) {
            val v = VALUES.first { it.ticks <= rest }
            out.add(v); rest -= v.ticks
        }
        if (out.isEmpty()) return listOf(NoteValue(ticks.coerceAtLeast(1), "64th"))
        if (rest > 0) out[out.lastIndex] = out.last().let { NoteValue(it.ticks + rest, it.type, it.dots, it.triplet) }
        return out
    }

    private val SHARP_NAMES = listOf("C" to 0, "C" to 1, "D" to 0, "D" to 1, "E" to 0, "F" to 0, "F" to 1, "G" to 0, "G" to 1, "A" to 0, "A" to 1, "B" to 0)
    private val FLAT_NAMES = listOf("C" to 0, "D" to -1, "D" to 0, "E" to -1, "E" to 0, "F" to 0, "G" to -1, "G" to 0, "A" to -1, "A" to 0, "B" to -1, "B" to 0)
    /** Step, alter and octave of a MIDI pitch, spelled with sharps in sharp keys and flats in flat keys. */
    fun spell(pitch: Int, key: Int): Triple<String, Int, Int> {
        val (step, alter) = (if (key < 0) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(pitch, 12)]
        return Triple(step, alter, pitch / 12 - 1)
    }

    /** Tracks accidentals within a measure so that only needed ones are printed. */
    private class Accidentals(private val key: Int) {
        private val current = HashMap<String, Int>()
        private val sharpOrder = "FCGDAEB"
        private fun keyAlter(step: String): Int = when {
            key > 0 && sharpOrder.indexOf(step) < key -> 1
            key < 0 && sharpOrder.reversed().indexOf(step) < -key -> -1
            else -> 0
        }
        fun mark(pitch: Int): String? {
            val (step, alter, octave) = spell(pitch, key)
            val id = "$step$octave"
            val before = current[id] ?: keyAlter(step)
            current[id] = alter
            if (before == alter) return null
            return when (alter) { 1 -> "sharp"; -1 -> "flat"; else -> "natural" }
        }
    }

    private fun q(beat: Double) = (beat * DIVISIONS).roundToInt()

    private fun measureOfTick(bounds: List<Int>, tick: Int): Int {
        var m = 0
        while (m + 1 < bounds.size - 1 && bounds[m + 1] <= tick) m++
        return m
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Guesses the key signature from the pitch classes used (best-fitting major/minor scale). */
object KeyGuess {
    fun fifths(notes: List<Note>): Int {
        if (notes.isEmpty()) return 0
        val hist = DoubleArray(12)
        for (n in notes) hist[Math.floorMod(n.pitch, 12)] += n.duration
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (f in -6..6) {
            val tonic = Math.floorMod(f * 7, 12)
            val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11).map { (tonic + it) % 12 }.toSet()
            val score = (0 until 12).sumOf { if (it in scale) hist[it] else -hist[it] } - abs(f) * 1e-3
            if (score > bestScore) { bestScore = score; best = f }
        }
        return best
    }
}
