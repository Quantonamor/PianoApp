package de.pianotiles.core.io

import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongUtils
import de.pianotiles.core.model.TempoChange
import kotlin.math.abs

/**
 * Reads ABC notation (standard 2.1, the common subset): header fields X T C M L Q K V, notes with accidentals
 * and octave marks, lengths, rests, chords `[CEG]`, ties, broken rhythm `>` `<`, tuplets `(3`, bar-local
 * accidentals, repeats `|: :|` with endings `[1 [2`, inline fields, several voices.
 * Grace notes, decorations, chord symbols and lyrics are skipped. The first tune of the file is used.
 *
 * Hands: with several voices the lowest voice (or the one with `clef=bass`) is the left hand.
 */
object AbcImporter {

    fun looksLikeAbc(text: String): Boolean {
        val lines = text.lineSequence().take(60).map { it.trim() }.toList()
        return lines.any { it.startsWith("X:") } && lines.any { it.startsWith("K:") }
    }

    private class Voice(val id: String) {
        var time = 0.0
        var bass = false
        val notes = ArrayList<Note>()
        val measures = arrayListOf(WrittenMeasure(0.0))
        val barAccidentals = HashMap<String, Int>()
        val ties = HashMap<Int, Int>() // pitch -> index of the note that continues
    }

    fun import(bytes: ByteArray, fallbackTitle: String): Song {
        val text = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
        if (!looksLikeAbc(text)) throw ImportException(t("Keine ABC-Noten (X: und K: fehlen)", "Not ABC notation (X: and K: missing)"))
        val lines = text.lines()
        val startIdx = lines.indexOfFirst { it.trim().startsWith("X:") }

        var title = ""
        var composer = ""
        var meterNum = 4; var meterDen = 4
        var unit: Double? = null
        var bpm = -1.0
        var key = 0
        val voices = LinkedHashMap<String, Voice>()
        var current: Voice? = null
        var inBody = false
        val tempos = ArrayList<TempoChange>()

        fun voice(id: String): Voice = voices.getOrPut(id) { Voice(id) }
        fun unitBeats(): Double = (unit ?: if (meterNum.toDouble() / meterDen < 0.75) 1.0 / 16 else 1.0 / 8) * 4.0

        fun applyField(name: Char, value: String, inline: Boolean) {
            val v = value.trim()
            when (name) {
                'T' -> if (title.isEmpty() && !inline) title = v
                'C' -> if (composer.isEmpty() && !inline) composer = v
                'M' -> parseMeter(v)?.let { (n, d) -> meterNum = n; meterDen = d }
                'L' -> parseFraction(v)?.let { unit = it }
                'Q' -> parseTempo(v)?.let { q ->
                    if (bpm < 0) bpm = q
                    else current?.let { tempos.add(TempoChange(it.time, q)) }
                }
                'K' -> {
                    key = parseKey(v)
                    if (Regex("(^|\\s)(clef=)?bass").containsMatchIn(v)) (current ?: voice("1")).bass = true
                    if (!inBody) { inBody = true; if (current == null) current = voice(voices.keys.firstOrNull() ?: "1") }
                }
                'V' -> {
                    val id = v.substringBefore(' ').ifEmpty { "1" }
                    val vv = voice(id)
                    if (Regex("clef=bass|\\bbass\\b|clef=F", RegexOption.IGNORE_CASE).containsMatchIn(v)) vv.bass = true
                    if (inBody) current = vv
                }
            }
        }

        for (raw in lines.drop(maxOf(0, startIdx))) {
            val line = raw.substringBefore('%').trimEnd()
            if (line.isBlank()) { if (inBody && (voices.values.sumOf { it.notes.size } > 0)) break else continue }
            if (line.trim().startsWith("X:") && inBody) break
            val field = Regex("^([A-Za-z]):(.*)$").find(line.trim())
            if (field != null && field.groupValues[1][0] !in "|") {
                val name = field.groupValues[1][0]
                if (name in "TCMLQKV") applyField(name, field.groupValues[2], inline = false)
                continue
            }
            if (!inBody) continue
            parseMusic(line.trimEnd('\\'), current ?: voice("1"), key, meterNum, meterDen, ::unitBeats,
                onInline = { n, v -> applyField(n, v, inline = true) }, voiceNow = { current!! })
        }

        val all = voices.values.filter { it.notes.isNotEmpty() }
        if (all.isEmpty()) throw ImportException(t("Die ABC-Datei enthält keine Noten", "The ABC file contains no notes"))
        val leftVoice: Voice? = when {
            all.size < 2 -> null
            all.any { it.bass } -> all.first { it.bass }
            else -> all.minBy { v -> v.notes.map { it.pitch }.average() }
        }
        val notes = all.flatMap { v -> v.notes.map { it.copy(hand = if (v === leftVoice) Hand.LEFT else Hand.RIGHT) } }

        val main = all.first()
        val measures = main.measures.filter { it.length > 1e-6 || it === main.measures.first() }
        // a first ending lasts until its backward repeat
        for (k in 1 until measures.size) {
            val prev = measures[k - 1]
            if (prev.endings.isNotEmpty() && prev.backwardTimes == 0 && measures[k].endings.isEmpty() && !measures[k].forward &&
                1 in prev.endings && measures.drop(k).any { it.backwardTimes > 0 }) measures[k].endings.addAll(prev.endings)
        }
        val nominal = meterNum * 4.0 / meterDen
        val pickup = measures.firstOrNull()?.length?.takeIf { it > 1e-6 && it < nominal - 1e-6 } ?: 0.0
        val (played, playedTempos) = Repeats.expand(notes, measures, tempos)
        return Song(
            id = SongUtils.newId(),
            title = title.ifBlank { fallbackTitle },
            composer = composer,
            bpm = if (bpm > 0) bpm else 120.0,
            beatsPerMeasure = meterNum,
            beatUnit = meterDen,
            notes = SongUtils.dedupe(played),
            tempoChanges = playedTempos.filter { it.beat > 1e-6 },
            source = SongSource.ABC,
            createdAt = System.currentTimeMillis(),
            pickupBeats = pickup,
            keyFifths = key,
        )
    }

    private val stepPc = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    private const val SHARP_ORDER = "FCGDAEB"

    private fun keyAlter(letter: Char, key: Int): Int = when {
        key > 0 && SHARP_ORDER.indexOf(letter) < key -> 1
        key < 0 && SHARP_ORDER.reversed().indexOf(letter) < -key -> -1
        else -> 0
    }

    /** Parses one line of music into [voice0] (or the voice chosen by inline `[V:…]`). */
    private fun parseMusic(
        line: String, voice0: Voice, key0: Int, meterNum: Int, meterDen: Int, unitBeats: () -> Double,
        onInline: (Char, String) -> Unit, voiceNow: () -> Voice,
    ) {
        var v = voice0
        var key = key0
        var i = 0
        var tupletLeft = 0
        var tupletFactor = 1.0
        var lastNoteIdx: List<Int> = emptyList() // indices of the previous note/chord (for broken rhythm)
        var brokenNext = 1.0
        val n = line.length

        fun closeMeasure(): WrittenMeasure {
            val m = v.measures.last()
            m.length = v.time - m.start
            v.barAccidentals.clear()
            val next = WrittenMeasure(v.time)
            v.measures.add(next)
            return next
        }

        fun readLength(): Double {
            var num = 0; var hasNum = false
            while (i < n && line[i].isDigit()) { num = num * 10 + (line[i] - '0'); i++; hasNum = true }
            var value = if (hasNum) num.toDouble() else 1.0
            while (i < n && line[i] == '/') {
                i++
                var den = 0; var hasDen = false
                while (i < n && line[i].isDigit()) { den = den * 10 + (line[i] - '0'); i++; hasDen = true }
                value /= if (hasDen) den.toDouble() else 2.0
            }
            return value
        }

        /** Reads accidentals + letter + octave marks; returns MIDI pitch or null. */
        fun readPitch(): Int? {
            var acc: Int? = null
            while (i < n && line[i] in "^_=") {
                acc = (acc ?: 0) + when (line[i]) { '^' -> 1; '_' -> -1; else -> 0 }
                if (line[i] == '=') acc = 0
                i++
            }
            if (i >= n || line[i].uppercaseChar() !in stepPc) return null
            val c = line[i]; i++
            val letter = c.uppercaseChar()
            var octave = if (c.isLowerCase()) 5 else 4
            while (i < n && (line[i] == '\'' || line[i] == ',')) { octave += if (line[i] == '\'') 1 else -1; i++ }
            val id = "$letter$octave"
            val alter = if (acc != null) { v.barAccidentals[id] = acc; acc } else v.barAccidentals[id] ?: keyAlter(letter, key)
            return (octave + 1) * 12 + stepPc.getValue(letter) + alter
        }

        fun addNote(pitch: Int, start: Double, dur: Double, tie: Boolean): Int {
            val cont = v.ties.remove(pitch)
            val idx = if (cont != null && abs(v.notes[cont].end - start) < 1e-6) {
                v.notes[cont] = v.notes[cont].copy(duration = v.notes[cont].duration + dur); cont
            } else {
                v.notes.add(Note(pitch, start, dur)); v.notes.lastIndex
            }
            if (tie) v.ties[pitch] = idx
            return idx
        }

        while (i < n) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' || c == '`' -> i++
                c == '"' -> { i = line.indexOf('"', i + 1).let { if (it < 0) n else it + 1 } } // chord symbol / annotation
                c == '!' -> { i = line.indexOf('!', i + 1).let { if (it < 0) n else it + 1 } }
                c == '+' -> { val e = line.indexOf('+', i + 1); i = if (e < 0) i + 1 else e + 1 }
                c == '{' -> { i = line.indexOf('}', i + 1).let { if (it < 0) n else it + 1 } } // grace notes
                c in "~.HLMOPSTuv" -> i++
                c == '[' && i + 2 < n && line[i + 1].isLetter() && line[i + 2] == ':' -> { // inline field
                    val e = line.indexOf(']', i).let { if (it < 0) n else it }
                    val name = line[i + 1]
                    val value = line.substring(i + 3, e)
                    onInline(name, value)
                    if (name == 'V') v = voiceNow()
                    if (name == 'K') key = parseKey(value)
                    i = e + 1
                }
                c == '[' && i + 1 < n && line[i + 1].isDigit() -> { // [1 [2 ending
                    i++
                    val nums = StringBuilder()
                    while (i < n && (line[i].isDigit() || line[i] == ',' || line[i] == '-')) nums.append(line[i++])
                    v.measures.last().endings.addAll(parseEndings(nums.toString()))
                }
                c == '|' || c == ':' || (c == '[' && i + 1 < n && line[i + 1] == '|') -> {
                    val sb = StringBuilder()
                    while (i < n && line[i] in "|:[]") {
                        if (line[i] == '[' && sb.isNotEmpty() && i + 1 < n && line[i + 1].isDigit()) break
                        sb.append(line[i++])
                    }
                    val bar = sb.toString()
                    val ending = StringBuilder()
                    while (i < n && (line[i].isDigit() || (ending.isNotEmpty() && line[i] in ",-"))) ending.append(line[i++])
                    if (bar == "[" || bar == "]") continue
                    val backward = bar.startsWith(":")
                    val forward = bar.endsWith(":") && bar.length > 1 || bar == "::"
                    val m = v.measures.last()
                    val hasContent = v.time - m.start > 1e-6
                    if (hasContent) {
                        if (backward) m.backwardTimes = 2
                        val next = closeMeasure()
                        if (forward) next.forward = true
                    } else {
                        // bar line at the start of a line / directly after another bar line
                        if (backward && v.measures.size >= 2) v.measures[v.measures.size - 2].backwardTimes = 2
                        if (forward) m.forward = true
                    }
                    if (ending.isNotEmpty()) v.measures.last().endings.addAll(parseEndings(ending.toString()))
                }
                c == '(' -> {
                    i++
                    if (i < n && line[i].isDigit()) {
                        val p = line[i++] - '0'
                        var q = when (p) { 2 -> 3; 3 -> 2; 4 -> 3; 6 -> 2; 8 -> 3; else -> if (meterNum % 3 == 0 && meterNum > 3) 3 else 2 }
                        var r = p
                        if (i < n && line[i] == ':') { i++; if (i < n && line[i].isDigit()) q = line[i++] - '0'
                            if (i < n && line[i] == ':') { i++; if (i < n && line[i].isDigit()) r = line[i++] - '0' } }
                        tupletLeft = r; tupletFactor = q.toDouble() / p
                    }
                }
                c == ')' -> i++
                c == '-' -> i++ // tie handled after notes
                c == '>' || c == '<' -> {
                    var k = 0
                    while (i < n && line[i] == c) { k++; i++ }
                    val shorten = 1.0 / (1 shl k)
                    val longer = 2.0 - shorten
                    val prevFactor = if (c == '>') longer else shorten
                    brokenNext = if (c == '>') shorten else longer
                    // lengthen/shorten the previous note or chord
                    if (lastNoteIdx.isNotEmpty()) {
                        val first = v.notes[lastNoteIdx.first()]
                        val oldDur = first.duration
                        val newDur = oldDur * prevFactor
                        for (idx in lastNoteIdx) v.notes[idx] = v.notes[idx].copy(duration = v.notes[idx].duration - oldDur + newDur)
                        v.time += newDur - oldDur
                    }
                }
                c == 'z' || c == 'x' || c == 'Z' || c == 'X' -> {
                    i++
                    val len = readLength()
                    val dur = if (c == 'Z' || c == 'X') len * meterNum * 4.0 / meterDen else len * unitBeats() * brokenNext * tuplet(tupletLeft, tupletFactor)
                    if (tupletLeft > 0) tupletLeft--
                    brokenNext = 1.0
                    if (c == 'Z' || c == 'X') {
                        // multi-measure rest: one measure per count
                        repeat(len.toInt().coerceAtLeast(1)) { v.time += meterNum * 4.0 / meterDen; closeMeasure() }
                    } else v.time += dur
                    lastNoteIdx = emptyList()
                }
                c == '[' -> { // chord
                    i++
                    val chordNotes = ArrayList<Pair<Int, Double>>()
                    while (i < n && line[i] != ']') {
                        val ch = line[i]
                        if (ch in "^_=" || ch.uppercaseChar() in stepPc) {
                            val p = readPitch() ?: break
                            val l = readLength()
                            val tie = i < n && line[i] == '-'
                            if (tie) i++
                            chordNotes.add((if (tie) -p else p) to l)
                        } else i++
                    }
                    i++ // ]
                    val mult = readLength()
                    if (chordNotes.isEmpty()) continue
                    val factor = unitBeats() * mult * brokenNext * tuplet(tupletLeft, tupletFactor)
                    if (tupletLeft > 0) tupletLeft--
                    brokenNext = 1.0
                    val tieAll = i < n && line[i] == '-'
                    if (tieAll) i++
                    val start = v.time
                    val idxs = chordNotes.map { (sp, l) -> addNote(abs(sp), start, l * factor, tieAll || sp < 0) }
                    v.time += chordNotes.first().second * factor
                    lastNoteIdx = idxs
                }
                c in "^_=" || c.uppercaseChar() in stepPc -> {
                    val p = readPitch()
                    if (p == null) { i++; continue }
                    val dur = readLength() * unitBeats() * brokenNext * tuplet(tupletLeft, tupletFactor)
                    if (tupletLeft > 0) tupletLeft--
                    brokenNext = 1.0
                    val tie = i < n && line[i] == '-'
                    if (tie) i++
                    lastNoteIdx = listOf(addNote(p, v.time, dur, tie))
                    v.time += dur
                }
                else -> i++
            }
        }
        // a line end does not close the measure; the last measure gets its length at the end of the tune
        v.measures.last().length = v.time - v.measures.last().start
    }

    private fun tuplet(left: Int, factor: Double) = if (left > 0) factor else 1.0

    private fun parseEndings(s: String): Set<Int> = s.split(',').flatMap { part ->
        val r = part.split('-').mapNotNull { it.trim().toIntOrNull() }
        if (r.size == 2) (r[0]..r[1]).toList() else r
    }.toSet()

    private fun parseMeter(v: String): Pair<Int, Int>? = when {
        v.startsWith("C|") -> 2 to 2
        v.startsWith("C") -> 4 to 4
        v.equals("none", true) -> null
        else -> Regex("(\\d+)(?:\\+\\d+)*/(\\d+)").find(v)?.let { m ->
            val num = m.value.substringBefore('/').split('+').sumOf { it.toInt() }
            num to m.groupValues[2].toInt()
        }
    }

    private fun parseFraction(v: String): Double? = Regex("(\\d+)/(\\d+)").find(v)?.let { it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }

    /** Tempo in quarter notes per minute. */
    private fun parseTempo(v: String): Double? {
        val clean = v.replace(Regex("\"[^\"]*\""), " ").trim()
        Regex("((?:\\d+/\\d+\\s*)+)=\\s*(\\d+(?:\\.\\d+)?)").find(clean)?.let { m ->
            val beat = Regex("(\\d+)/(\\d+)").findAll(m.groupValues[1]).sumOf { it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
            return m.groupValues[2].toDouble() * beat * 4
        }
        return Regex("^(\\d+(?:\\.\\d+)?)$").find(clean)?.groupValues?.get(1)?.toDouble()
    }

    /** Key signature (fifths) from a K: field like `G`, `Dm`, `Bb mix`, `F#min`, `none`. */
    fun parseKey(v: String): Int {
        val s = v.trim()
        val m = Regex("^([A-G])([#b]?)\\s*([A-Za-z]*)").find(s) ?: return 0
        val tonic = mapOf("C" to 0, "G" to 1, "D" to 2, "A" to 3, "E" to 4, "B" to 5, "F" to -1)
        var f = tonic.getValue(m.groupValues[1]) + when (m.groupValues[2]) { "#" -> 7; "b" -> -7; else -> 0 }
        val mode = m.groupValues[3].lowercase()
        f += when {
            mode.startsWith("mix") -> -1
            mode.startsWith("dor") -> -2
            mode.startsWith("phr") -> -4
            mode.startsWith("lyd") -> 1
            mode.startsWith("loc") -> -5
            mode.startsWith("aeo") || mode == "m" || mode.startsWith("min") -> -3
            else -> 0
        }
        return f.coerceIn(-7, 7)
    }
}
