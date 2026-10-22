package de.pianotiles.core.io

import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongUtils
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Reads MusicXML (score-partwise), plain (.xml/.musicxml) or compressed (.mxl).
 *
 * Supported: pitches, alterations, chords, ties, backup/forward (multiple voices), several staves,
 * grace notes (skipped), tempo, time and key signatures. Repeats are played as written (not expanded).
 */
object MusicXmlImporter {

    private val stepIndex = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun import(bytes: ByteArray, fallbackTitle: String): Song {
        val xmlBytes = if (isZip(bytes)) extractFromMxl(bytes) else bytes
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        val builder = factory.newDocumentBuilder()
        // Never fetch the external MusicXML DTD from the network.
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val doc = try {
            builder.parse(ByteArrayInputStream(xmlBytes))
        } catch (e: Exception) {
            throw ImportException(t("MusicXML konnte nicht gelesen werden: ${e.message}", "Could not read MusicXML: ${e.message}"), e)
        }
        val root = doc.documentElement
        if (root.tagName == "score-timewise") throw ImportException(t("MusicXML 'timewise' wird nicht unterstützt – bitte als 'partwise' exportieren", "MusicXML 'timewise' is not supported – please export as 'partwise'"))
        if (root.tagName != "score-partwise") throw ImportException(t("Keine MusicXML-Partitur", "Not a MusicXML score"))

        val title = root.child("work")?.child("work-title")?.text()
            ?: root.child("movement-title")?.text()
            ?: fallbackTitle
        val composer = root.child("identification")?.children("creator")
            ?.firstOrNull { it.getAttribute("type") == "composer" }?.text() ?: ""

        var bpm = -1.0
        var beats = 4
        var beatType = 4
        val parts = root.children("part")
        val notesByPart = ArrayList<List<Pair<Note, Int>>>() // note + staff number
        var maxStaves = 1
        var pickup = 0.0
        var keyFifths: Int? = null
        val measures = ArrayList<MeasureInfo>() // from the first part
        val tempos = ArrayList<de.pianotiles.core.model.TempoChange>()
        val pedalEvents = ArrayList<Pair<Double, String>>() // beat, "start" / "stop" / "change"

        for (part in parts) {
            val partNotes = ArrayList<Pair<Note, Int>>()
            var divisions = 1.0
            var measureStart = 0.0
            val tieOpen = HashMap<Int, Int>() // pitch -> index in partNotes
            for ((measureIdx, measure) in part.children("measure").withIndex()) {
                val info = MeasureInfo(measureStart)
                if (parts.indexOf(part) == 0) {
                    for (bl in measure.children("barline")) {
                        bl.child("repeat")?.let { r ->
                            if (r.getAttribute("direction") == "forward") info.forward = true
                            if (r.getAttribute("direction") == "backward") info.backwardTimes = r.getAttribute("times").toIntOrNull() ?: 2
                        }
                        bl.child("ending")?.let { e ->
                            if (e.getAttribute("type") == "start") e.getAttribute("number").split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.let { info.endings.addAll(it) }
                        }
                    }
                }
                var cursor = 0.0
                var lastNoteStart = 0.0
                var measureLength = 0.0
                for (el in measure.childElements()) {
                    when (el.tagName) {
                        "attributes" -> {
                            el.child("divisions")?.text()?.toDoubleOrNull()?.let { divisions = it }
                            el.child("time")?.let { t ->
                                if (parts.indexOf(part) == 0 && measureStart == 0.0) {
                                    t.child("beats")?.text()?.substringBefore('+')?.toIntOrNull()?.let { beats = it }
                                    t.child("beat-type")?.text()?.toIntOrNull()?.let { beatType = it }
                                }
                            }
                            el.child("staves")?.text()?.toIntOrNull()?.let { maxStaves = maxOf(maxStaves, it) }
                            if (keyFifths == null) el.child("key")?.child("fifths")?.text()?.toIntOrNull()?.let { keyFifths = it }
                        }
                        "direction" -> {
                            // sustain pedal marks ("Ped." … "*"), from any part
                            for (pd in el.descendants("pedal")) {
                                val type = pd.getAttribute("type")
                                if (type == "start" || type == "stop" || type == "change") pedalEvents.add(measureStart + cursor to type)
                            }
                            val t = el.child("sound")?.getAttribute("tempo")?.toDoubleOrNull()
                                ?: el.descendants("per-minute").firstOrNull()?.text()?.toDoubleOrNull()
                            if (t != null && t > 0) {
                                if (bpm < 0) bpm = t
                                if (parts.indexOf(part) == 0) tempos.add(de.pianotiles.core.model.TempoChange(measureStart + cursor, t))
                            }
                        }
                        "sound" -> el.getAttribute("tempo").toDoubleOrNull()?.let { t ->
                            if (bpm < 0) bpm = t
                            if (parts.indexOf(part) == 0) tempos.add(de.pianotiles.core.model.TempoChange(measureStart + cursor, t))
                        }
                        "backup" -> cursor -= (el.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                        "forward" -> {
                            cursor += (el.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                            measureLength = maxOf(measureLength, cursor)
                        }
                        "note" -> {
                            if (el.child("grace") != null) continue
                            val dur = (el.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                            val isChord = el.child("chord") != null
                            val start = if (isChord) lastNoteStart else cursor
                            if (!isChord) cursor += dur
                            measureLength = maxOf(measureLength, cursor)
                            lastNoteStart = start
                            val pitchEl = el.child("pitch") ?: continue // rest or unpitched
                            val step = pitchEl.child("step")?.text()?.firstOrNull() ?: continue
                            val alter = pitchEl.child("alter")?.text()?.toDoubleOrNull()?.toInt() ?: 0
                            val octave = pitchEl.child("octave")?.text()?.toIntOrNull() ?: 4
                            val midi = (octave + 1) * 12 + (stepIndex[step.uppercaseChar()] ?: 0) + alter
                            val staff = el.child("staff")?.text()?.toIntOrNull() ?: 1
                            val ties = el.children("tie").map { it.getAttribute("type") }
                            val finger = el.child("notations")?.child("technical")?.child("fingering")?.text()
                                ?.filter { it.isDigit() }?.take(1)?.toIntOrNull()?.takeIf { it in 1..5 } ?: 0
                            val absStart = measureStart + start
                            if ("stop" in ties && tieOpen.containsKey(midi)) {
                                val idx = tieOpen.getValue(midi)
                                val (prev, st) = partNotes[idx]
                                partNotes[idx] = prev.copy(duration = absStart + dur - prev.start) to st
                                if ("start" !in ties) tieOpen.remove(midi)
                            } else {
                                partNotes.add(Note(midi, absStart, dur.coerceAtLeast(1.0 / 32), finger = finger) to staff)
                                if ("start" in ties) tieOpen[midi] = partNotes.lastIndex
                            }
                        }
                    }
                }
                val nominal = beats * 4.0 / beatType
                if (measureIdx == 0 && parts.indexOf(part) == 0 && measureLength > 0 && measureLength < nominal - 1e-6) {
                    pickup = measureLength
                }
                // Pick-up measures are shorter; use the actual content length when it is shorter.
                val len = if (measureLength > 0) measureLength else nominal
                if (parts.indexOf(part) == 0) { info.length = len; measures.add(info) }
                measureStart += len
            }
            notesByPart.add(partNotes)
        }

        val allNotes = ArrayList<Note>()
        val pianoParts = notesByPart.filter { it.isNotEmpty() }
        when {
            pianoParts.isEmpty() -> throw ImportException(t("Die MusicXML-Datei enthält keine Noten", "The MusicXML file contains no notes"))
            pianoParts.size == 1 && maxStaves >= 2 ->
                pianoParts[0].forEach { (n, staff) -> allNotes.add(n.copy(hand = if (staff >= 2) Hand.LEFT else Hand.RIGHT)) }
            pianoParts.size == 2 -> {
                pianoParts[0].forEach { allNotes.add(it.first.copy(hand = Hand.RIGHT)) }
                pianoParts[1].forEach { allNotes.add(it.first.copy(hand = Hand.LEFT)) }
            }
            else -> allNotes.addAll(SongUtils.splitHands(pianoParts.flatten().map { it.first }))
        }

        val (played, playedTempos) = expandRepeats(allNotes, measures, tempos)
        val playedPedal = expandRepeatEvents(pedalEvents.sortedBy { it.first }, measures)
        return Song(
            id = SongUtils.newId(),
            title = title.trim().ifEmpty { fallbackTitle },
            composer = composer.trim(),
            bpm = if (bpm > 0) bpm else 100.0,
            beatsPerMeasure = beats,
            beatUnit = beatType,
            notes = SongUtils.dedupe(played),
            tempoChanges = playedTempos.distinctBy { it.beat }.filter { it.beat > 1e-6 },
            source = SongSource.MUSICXML,
            createdAt = System.currentTimeMillis(),
            pickupBeats = pickup,
            keyFifths = keyFifths,
            fingeringSource = if (played.any { it.finger > 0 }) de.pianotiles.core.model.FingeringSource.FILE else de.pianotiles.core.model.FingeringSource.NONE,
            pedal = pedalSpans(playedPedal),
        )
    }

    /** Pedal marks → spans; "change" lifts and presses again at the same moment. */
    private fun pedalSpans(events: List<Pair<Double, String>>): List<de.pianotiles.core.model.PedalSpan> {
        val out = ArrayList<de.pianotiles.core.model.PedalSpan>()
        var downAt = -1.0
        for ((beat, type) in events) {
            when (type) {
                "start" -> if (downAt < 0) downAt = beat
                "stop" -> { if (downAt >= 0 && beat > downAt) out.add(de.pianotiles.core.model.PedalSpan(downAt, beat)); downAt = -1.0 }
                "change" -> { if (downAt >= 0 && beat > downAt) out.add(de.pianotiles.core.model.PedalSpan(downAt, beat)); downAt = beat }
            }
        }
        return out
    }

    /** Timed events in playing order (repeats expanded like the notes). */
    private fun <T> expandRepeatEvents(events: List<Pair<Double, T>>, measures: List<MeasureInfo>): List<Pair<Double, T>> {
        if (measures.none { it.backwardTimes > 0 } || events.isEmpty()) return events
        val order = playOrder(measures.map { it.forward }, measures.map { it.backwardTimes }, measures.map { it.endings })
        fun measureOf(beat: Double) = measures.indexOfLast { it.start <= beat + 1e-6 }.coerceAtLeast(0)
        val byMeasure = events.groupBy { measureOf(it.first) }
        val out = ArrayList<Pair<Double, T>>()
        var t = 0.0
        for (m in order) {
            val info = measures[m]
            byMeasure[m]?.forEach { out.add(t + it.first - info.start to it.second) }
            t += info.length
        }
        return out
    }

    private class MeasureInfo(val start: Double) {
        var length = 0.0
        var forward = false
        var backwardTimes = 0
        val endings = HashSet<Int>()
    }

    /**
     * Order in which measures are played: a backward repeat jumps back to the last forward repeat (or the
     * start); measures inside 1st/2nd endings are only played in the matching pass.
     */
    internal fun playOrder(forward: List<Boolean>, backwardTimes: List<Int>, endings: List<Set<Int>>): List<Int> {
        val order = ArrayList<Int>()
        var i = 0
        var sectionStart = 0
        var pass = 1
        var jumped = false
        val made = HashMap<Int, Int>()
        while (i < forward.size && order.size < 20_000) {
            if (forward[i] && !jumped) { sectionStart = i; pass = 1 }
            jumped = false
            if (endings[i].isNotEmpty() && pass !in endings[i]) { i++; continue }
            order.add(i)
            if (backwardTimes[i] > 0 && (made[i] ?: 0) < backwardTimes[i] - 1) {
                made[i] = (made[i] ?: 0) + 1
                pass++
                i = sectionStart
                jumped = true
                continue
            }
            if (backwardTimes[i] > 0) { sectionStart = i + 1; pass = 1 }
            i++
        }
        return order
    }

    private fun expandRepeats(
        notes: List<Note>, measures: List<MeasureInfo>, tempos: List<de.pianotiles.core.model.TempoChange>,
    ): Pair<List<Note>, List<de.pianotiles.core.model.TempoChange>> {
        if (measures.none { it.backwardTimes > 0 }) return notes to tempos
        val order = playOrder(measures.map { it.forward }, measures.map { it.backwardTimes }, measures.map { it.endings })
        fun measureOf(beat: Double) = measures.indexOfLast { it.start <= beat + 1e-6 }.coerceAtLeast(0)
        val byMeasure = notes.groupBy { measureOf(it.start) }
        val temposByMeasure = tempos.groupBy { measureOf(it.beat) }
        val out = ArrayList<Note>()
        val outTempos = ArrayList<de.pianotiles.core.model.TempoChange>()
        var t = 0.0
        for (m in order) {
            val info = measures[m]
            byMeasure[m]?.forEach { out.add(it.copy(start = t + it.start - info.start)) }
            temposByMeasure[m]?.forEach { outTempos.add(it.copy(beat = t + it.beat - info.start)) }
            t += info.length
        }
        return out to outTempos
    }

    private fun extractFromMxl(bytes: ByteArray): ByteArray {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
            }
        }
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val rootPath = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        return rootPath?.let { entries[it] }
            ?: entries.entries.firstOrNull { !it.key.startsWith("META-INF") && (it.key.endsWith(".xml") || it.key.endsWith(".musicxml")) }?.value
            ?: throw ImportException(t("In der .mxl-Datei wurde keine Partitur gefunden", "No score found in the .mxl file"))
    }

    // --- small DOM helpers ---
    private fun Element.childElements(): List<Element> {
        val list = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { list.add(it) }
        return list
    }

    private fun Element.child(name: String): Element? = childElements().firstOrNull { it.tagName == name }
    private fun Element.children(name: String): List<Element> = childElements().filter { it.tagName == name }
    private fun Element.descendants(name: String): List<Element> {
        val nl = getElementsByTagName(name)
        return (0 until nl.length).mapNotNull { nl.item(it) as? Element }
    }
    private fun Element.text(): String = textContent.trim()
}
