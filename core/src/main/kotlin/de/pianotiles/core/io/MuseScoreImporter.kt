package de.pianotiles.core.io

import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongUtils
import de.pianotiles.core.model.TempoChange
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Reads MuseScore files: `.mscz` (ZIP) and `.mscx` (XML), versions 3 and 4 (version 2 roughly).
 * Supported: notes and chords per voice, rests, dots, tuplets, ties, grace notes (skipped), time and key
 * signature, tempo, pick-up measures (`len`), repeats with voltas. A piano part with two staves gives the
 * hands; otherwise the lowest staff is the left hand.
 */
object MuseScoreImporter {

    fun looksLikeMscx(head: String) = head.contains("<museScore")

    fun import(bytes: ByteArray, fallbackTitle: String): Song {
        val xml = if (MusicXmlImporter.isZip(bytes)) extractMscx(bytes) else bytes
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val doc = try { builder.parse(ByteArrayInputStream(xml)) } catch (e: Exception) {
            throw ImportException(t("MuseScore-Datei konnte nicht gelesen werden: ${e.message}", "Could not read the MuseScore file: ${e.message}"), e)
        }
        val root = doc.documentElement
        if (root.tagName != "museScore") throw ImportException(t("Keine MuseScore-Datei", "Not a MuseScore file"))
        val score = root.child("Score") ?: throw ImportException(t("Keine MuseScore-Partitur", "No MuseScore score"))

        fun meta(name: String) = score.children("metaTag").firstOrNull { it.getAttribute("name") == name }?.text().orEmpty()
        val title = meta("workTitle").ifBlank { score.descendants("Text").firstOrNull { it.child("style")?.text() == "title" }?.child("text")?.text().orEmpty() }
        val composer = meta("composer")

        // staff ids in order; parts tell which staves belong together
        val partStaves = score.children("Part").map { p -> p.children("Staff").map { it.getAttribute("id") } }
        val staffEls = score.children("Staff")
        if (staffEls.isEmpty()) throw ImportException(t("Die MuseScore-Datei enthält keine Noten", "The MuseScore file contains no notes"))

        var beats = 4; var beatType = 4
        var key: Int? = null
        var bpm = -1.0
        val tempos = ArrayList<TempoChange>()
        val pedal = ArrayList<de.pianotiles.core.model.PedalSpan>()
        var pedalDown = -1.0
        val measures = ArrayList<WrittenMeasure>()
        val staffNotes = LinkedHashMap<String, MutableList<Note>>()

        for ((si, staff) in staffEls.withIndex()) {
            val id = staff.getAttribute("id").ifEmpty { "${si + 1}" }
            val notes = ArrayList<Note>()
            staffNotes[id] = notes
            var measureStart = 0.0
            var curBeats = 4; var curType = 4
            var voltaEndings: Set<Int> = emptySet()
            val openTies = HashMap<Int, Int>()
            var dynamic = 80 // velocity of the current dynamic marking
            for ((mi, m) in staff.children("Measure").withIndex()) {
                val first = si == 0
                val info = if (first) WrittenMeasure(measureStart).also { measures.add(it) } else measures.getOrNull(mi)
                if (first && info != null) {
                    if (m.child("startRepeat") != null) info.forward = true
                    m.child("endRepeat")?.let { info.backwardTimes = it.text().toIntOrNull() ?: 2 }
                }
                val voices = m.children("voice").ifEmpty { listOf(m) }
                // time signature first (it may sit in any voice)
                for (v in voices) v.child("TimeSig")?.let { ts ->
                    curBeats = ts.child("sigN")?.text()?.toIntOrNull() ?: curBeats
                    curType = ts.child("sigD")?.text()?.toIntOrNull() ?: curType
                    if (first && mi == 0) { beats = curBeats; beatType = curType }
                }
                val nominal = curBeats * 4.0 / curType
                // the first staff defines the measure lengths (other staves often repeat no time signature)
                val len = if (!first && info != null) info.length
                else m.getAttribute("len").takeIf { it.isNotEmpty() }?.let { fraction(it) } ?: nominal
                for (v in voices) {
                    var cursor = 0.0
                    var tuplet = 1.0
                    val tupletById = HashMap<String, Double>()
                    for (el in v.childElements()) {
                        when (el.tagName) {
                            "KeySig" -> if (key == null) (el.child("concertKey") ?: el.child("accidental") ?: el.child("subtype"))?.text()?.toIntOrNull()?.let { key = it }
                            "Tempo" -> el.child("tempo")?.text()?.toDoubleOrNull()?.let { qps ->
                                val q = qps * 60
                                if (bpm < 0) bpm = q
                                if (first) tempos.add(TempoChange(measureStart + cursor, q))
                            }
                            "location" -> el.child("fractions")?.text()?.let { cursor += fraction(it) }
                            "Tuplet" -> {
                                val normal = el.child("normalNotes")?.text()?.toDoubleOrNull() ?: 2.0
                                val actual = el.child("actualNotes")?.text()?.toDoubleOrNull() ?: 3.0
                                // MuseScore 3 refers to tuplets by id, MuseScore 4 encloses them with <endTuplet/>
                                val id = el.getAttribute("id")
                                if (id.isNotEmpty()) tupletById[id] = normal / actual else tuplet = normal / actual
                            }
                            "endTuplet" -> tuplet = 1.0
                            "Spanner" -> if (first && el.getAttribute("type") == "Volta") {
                                val endings = el.child("Volta")?.child("endings")?.text()?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                                if (endings != null && el.child("next") != null) voltaEndings = endings
                                else if (el.child("prev") != null) voltaEndings = emptySet()
                            } else if (el.getAttribute("type") == "Pedal") {
                                // sustain pedal: the spanner starts where <next> is given and ends where <prev> is
                                if (el.child("next") != null) pedalDown = measureStart + cursor
                                else if (el.child("prev") != null && pedalDown >= 0) {
                                    if (measureStart + cursor > pedalDown) pedal.add(de.pianotiles.core.model.PedalSpan(pedalDown, measureStart + cursor))
                                    pedalDown = -1.0
                                }
                            }
                            "Dynamic" -> {
                                val sub = el.child("subtype")?.text().orEmpty()
                                dynamic = el.child("velocity")?.text()?.toIntOrNull() ?: DYNAMICS[sub] ?: dynamic
                            }
                            "Rest", "Chord" -> {
                                val type = el.child("durationType")?.text() ?: "quarter"
                                val ownTuplet = el.child("Tuplet")?.text()?.let { tupletById[it] }
                                val dur = if (type == "measure") (el.child("duration")?.text()?.let { fraction(it) } ?: len)
                                else baseLength(type) * dots(el.child("dots")?.text()?.toIntOrNull() ?: 0) * (ownTuplet ?: tuplet)
                                if (el.tagName == "Chord" && isGrace(el)) continue
                                if (el.tagName == "Chord") {
                                    val start = measureStart + cursor
                                    for (n in el.children("Note")) {
                                        val pitch = n.child("pitch")?.text()?.toIntOrNull() ?: continue
                                        val cont = openTies.remove(pitch)
                                        val idx = if (cont != null && kotlin.math.abs(notes[cont].end - start) < 1e-4) {
                                            notes[cont] = notes[cont].copy(duration = notes[cont].duration + dur); cont
                                        } else {
                                            val finger = n.children("Fingering").firstNotNullOfOrNull { it.child("text")?.text()?.toIntOrNull() }?.takeIf { it in 1..5 } ?: 0
                                            val vel = n.child("velocity")?.text()?.toIntOrNull()?.takeIf { it in 1..127 } ?: dynamic
                                            notes.add(Note(pitch, start, dur, velocity = vel.coerceIn(16, 127), finger = finger)); notes.lastIndex
                                        }
                                        val tieStart = n.child("Tie") != null ||
                                            n.children("Spanner").any { it.getAttribute("type") == "Tie" && it.child("next") != null }
                                        if (tieStart) openTies[pitch] = idx
                                    }
                                }
                                cursor += dur
                            }
                        }
                    }
                }
                if (first && info != null) {
                    info.length = len
                    if (voltaEndings.isNotEmpty()) info.endings.addAll(voltaEndings)
                }
                measureStart += len
            }
        }

        // hands: the first part with two staves is the piano
        val piano = partStaves.firstOrNull { it.size >= 2 }
        val all = ArrayList<Note>()
        val withNotes = staffNotes.filterValues { it.isNotEmpty() }
        if (withNotes.isEmpty()) throw ImportException(t("Die MuseScore-Datei enthält keine Noten", "The MuseScore file contains no notes"))
        if (piano != null) {
            staffNotes[piano[0]]?.forEach { all.add(it.copy(hand = Hand.RIGHT)) }
            piano.drop(1).forEach { sid -> staffNotes[sid]?.forEach { all.add(it.copy(hand = Hand.LEFT)) } }
        } else if (withNotes.size >= 2) {
            val lowest = withNotes.minBy { (_, ns) -> ns.map { it.pitch }.average() }.key
            withNotes.forEach { (sid, ns) -> ns.forEach { all.add(it.copy(hand = if (sid == lowest) Hand.LEFT else Hand.RIGHT)) } }
        } else all.addAll(withNotes.values.first())

        val nominal = beats * 4.0 / beatType
        val pickup = measures.firstOrNull()?.length?.takeIf { it < nominal - 1e-6 } ?: 0.0
        val (played, playedTempos) = Repeats.expand(all, measures, tempos)
        // pedal in playing order: down/up events through the repeats, then spans again
        val pedalEvents = Repeats.expandEvents(pedal.flatMap { listOf(it.start to true, it.end to false) }.sortedBy { it.first }, measures)
        val playedPedal = ArrayList<de.pianotiles.core.model.PedalSpan>()
        var downAt = -1.0
        for ((b, down) in pedalEvents) if (down) downAt = b else if (downAt >= 0) { if (b > downAt) playedPedal.add(de.pianotiles.core.model.PedalSpan(downAt, b)); downAt = -1.0 }
        return Song(
            id = SongUtils.newId(),
            title = title.ifBlank { fallbackTitle },
            composer = composer,
            bpm = if (bpm > 0) bpm else 100.0,
            beatsPerMeasure = beats,
            beatUnit = beatType,
            notes = SongUtils.dedupe(played),
            tempoChanges = playedTempos.distinctBy { it.beat }.filter { it.beat > 1e-6 },
            source = SongSource.MUSESCORE,
            createdAt = System.currentTimeMillis(),
            pickupBeats = pickup,
            keyFifths = key,
            fingeringSource = if (played.any { it.finger > 0 }) de.pianotiles.core.model.FingeringSource.FILE else de.pianotiles.core.model.FingeringSource.NONE,
            pedal = playedPedal,
        )
    }

    /** Velocity of MuseScore's dynamic markings (when the file gives none). */
    private val DYNAMICS = mapOf("pppp" to 16, "ppp" to 24, "pp" to 36, "p" to 50, "mp" to 64, "mf" to 80, "f" to 96, "ff" to 110, "fff" to 120, "ffff" to 127, "sfz" to 112, "sf" to 112, "fz" to 112)

    private fun isGrace(el: Element) = el.childElements().any {
        it.tagName in setOf("acciaccatura", "appoggiatura", "grace4", "grace16", "grace32", "grace8after", "grace16after", "grace32after")
    }

    private fun baseLength(type: String): Double = when (type) {
        "long" -> 16.0; "breve" -> 8.0; "whole" -> 4.0; "half" -> 2.0; "quarter" -> 1.0; "eighth" -> 0.5
        "16th" -> 0.25; "32nd" -> 0.125; "64th" -> 0.0625; "128th" -> 0.03125
        else -> 1.0
    }

    private fun dots(n: Int): Double { var f = 1.0; var add = 0.5; repeat(n) { f += add; add /= 2 }; return f }

    /** "3/4" (of a whole note) → beats in quarter notes. */
    private fun fraction(s: String): Double {
        val parts = s.trim().split('/')
        val num = parts[0].toDoubleOrNull() ?: 0.0
        val den = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return num / den * 4.0
    }

    private fun extractMscx(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".mscx") && !e.name.contains("/")) return zip.readBytes()
            }
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".mscx")) return zip.readBytes()
            }
        }
        throw ImportException(t("In der .mscz-Datei wurde keine Partitur gefunden", "No score found in the .mscz file"))
    }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { out.add(it) }
        return out
    }
    private fun Element.child(name: String): Element? = childElements().firstOrNull { it.tagName == name }
    private fun Element.children(name: String): List<Element> = childElements().filter { it.tagName == name }
    private fun Element.descendants(name: String): List<Element> {
        val nl = getElementsByTagName(name)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
    private fun Element.text(): String = textContent.trim()
}
