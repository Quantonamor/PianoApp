package de.pianotiles.core.io

import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongUtils
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

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
            throw ImportException("MusicXML konnte nicht gelesen werden: ${e.message}", e)
        }
        val root = doc.documentElement
        if (root.tagName == "score-timewise") throw ImportException("MusicXML 'timewise' wird nicht unterstützt – bitte als 'partwise' exportieren")
        if (root.tagName != "score-partwise") throw ImportException("Keine MusicXML-Partitur")

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

        for (part in parts) {
            val partNotes = ArrayList<Pair<Note, Int>>()
            var divisions = 1.0
            var measureStart = 0.0
            val tieOpen = HashMap<Int, Int>() // pitch -> index in partNotes
            for ((measureIdx, measure) in part.children("measure").withIndex()) {
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
                        }
                        "direction" -> {
                            if (bpm < 0) {
                                el.child("sound")?.getAttribute("tempo")?.toDoubleOrNull()?.let { bpm = it }
                                el.descendants("per-minute").firstOrNull()?.text()?.toDoubleOrNull()?.let { if (bpm < 0) bpm = it }
                            }
                        }
                        "sound" -> if (bpm < 0) el.getAttribute("tempo").toDoubleOrNull()?.let { bpm = it }
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
                            val absStart = measureStart + start
                            if ("stop" in ties && tieOpen.containsKey(midi)) {
                                val idx = tieOpen.getValue(midi)
                                val (prev, st) = partNotes[idx]
                                partNotes[idx] = prev.copy(duration = absStart + dur - prev.start) to st
                                if ("start" !in ties) tieOpen.remove(midi)
                            } else {
                                partNotes.add(Note(midi, absStart, dur.coerceAtLeast(1.0 / 32)) to staff)
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
                measureStart += if (measureLength > 0) measureLength else nominal
            }
            notesByPart.add(partNotes)
        }

        val allNotes = ArrayList<Note>()
        val pianoParts = notesByPart.filter { it.isNotEmpty() }
        when {
            pianoParts.isEmpty() -> throw ImportException("Die MusicXML-Datei enthält keine Noten")
            pianoParts.size == 1 && maxStaves >= 2 ->
                pianoParts[0].forEach { (n, staff) -> allNotes.add(n.copy(hand = if (staff >= 2) Hand.LEFT else Hand.RIGHT)) }
            pianoParts.size == 2 -> {
                pianoParts[0].forEach { allNotes.add(it.first.copy(hand = Hand.RIGHT)) }
                pianoParts[1].forEach { allNotes.add(it.first.copy(hand = Hand.LEFT)) }
            }
            else -> allNotes.addAll(SongUtils.splitHands(pianoParts.flatten().map { it.first }))
        }

        return Song(
            id = SongUtils.newId(),
            title = title.trim().ifEmpty { fallbackTitle },
            composer = composer.trim(),
            bpm = if (bpm > 0) bpm else 100.0,
            beatsPerMeasure = beats,
            beatUnit = beatType,
            notes = SongUtils.dedupe(allNotes),
            source = SongSource.MUSICXML,
            createdAt = System.currentTimeMillis(),
            pickupBeats = pickup,
        )
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
            ?: throw ImportException("In der .mxl-Datei wurde keine Partitur gefunden")
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
