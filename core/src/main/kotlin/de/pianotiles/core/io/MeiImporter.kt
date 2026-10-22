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
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Reads MEI (Music Encoding Initiative, versions 3–5): measures → staves → layers with notes, chords, rests,
 * beams, tuplets, ties (`@tie` and `<tie>`), gestural/written accidentals and the key signature, meter,
 * tempo, repeats and endings. Staff 1 is the right hand, staff 2 the left hand.
 */
object MeiImporter {

    fun looksLikeMei(head: String) = head.contains("<mei") && head.contains("music-encoding.org")

    private val pc = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

    fun import(bytes: ByteArray, fallbackTitle: String): Song {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val doc = try { builder.parse(ByteArrayInputStream(bytes)) } catch (e: Exception) {
            throw ImportException(t("MEI-Datei konnte nicht gelesen werden: ${e.message}", "Could not read the MEI file: ${e.message}"), e)
        }
        val root = doc.documentElement
        if (root.tagName != "mei") throw ImportException(t("Keine MEI-Datei", "Not an MEI file"))
        val title = root.first("titleStmt")?.child("title")?.text().orEmpty()
        val composer = (root.first("titleStmt")?.children("composer")?.firstOrNull()?.text()
            ?: root.first("titleStmt")?.first("persName")?.takeIf { it.getAttribute("role") == "composer" }?.text()).orEmpty()

        val scoreDef = root.first("scoreDef")
        var beats = (scoreDef?.attr("meter.count") ?: scoreDef?.first("meterSig")?.attr("count"))?.toIntOrNull() ?: 4
        var beatType = (scoreDef?.attr("meter.unit") ?: scoreDef?.first("meterSig")?.attr("unit"))?.toIntOrNull() ?: 4
        val key = keySig(scoreDef?.attr("key.sig") ?: scoreDef?.first("keySig")?.attr("sig"))
        var bpm = scoreDef?.attr("midi.bpm")?.toDoubleOrNull() ?: -1.0

        val staffOrder = root.all("staffDef").mapNotNull { it.attr("n") }.distinct()
        val notesByStaff = LinkedHashMap<String, MutableList<Note>>()
        val measures = ArrayList<WrittenMeasure>()
        val tempos = ArrayList<TempoChange>()
        val tieStarts = HashSet<String>() // xml:ids of notes that start a tie (from <tie> elements)
        root.all("tie").forEach { it.attr("startid")?.removePrefix("#")?.let(tieStarts::add) }

        var t0 = 0.0
        val openTies = HashMap<Pair<String, Int>, Int>()
        val body = root.first("body") ?: throw ImportException(t("Die MEI-Datei enthält keine Noten", "The MEI file contains no notes"))
        val measureEls = ArrayList<Pair<Element, Set<Int>>>()
        fun collect(el: Element, endings: Set<Int>) {
            for (c in el.childElements()) when (c.tagName) {
                "measure" -> measureEls.add(c to endings)
                "ending" -> collect(c, c.attr("n")?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet())
                "scoreDef" -> { c.attr("meter.count")?.toIntOrNull()?.let { if (measureEls.isEmpty()) beats = it }
                    c.attr("meter.unit")?.toIntOrNull()?.let { if (measureEls.isEmpty()) beatType = it } }
                else -> collect(c, endings)
            }
        }
        collect(body, emptySet())

        for ((mi, pair) in measureEls.withIndex()) {
            val (m, endings) = pair
            val info = WrittenMeasure(t0)
            if (m.attr("left") == "rptstart") info.forward = true
            if (m.attr("right") == "rptend" || m.attr("right") == "rptboth") info.backwardTimes = 2
            info.endings.addAll(endings)
            if (measureEls.getOrNull(mi - 1)?.first?.attr("right") == "rptboth") info.forward = true
            var maxLen = 0.0
            for (st in m.children("staff")) {
                val n = st.attr("n") ?: "1"
                val notes = notesByStaff.getOrPut(n) { ArrayList() }
                for (layer in st.children("layer")) {
                    val barAcc = HashMap<String, Int>()
                    var cursor = 0.0
                    fun walk(el: Element, ratio: Double, inheritedDur: String?, inheritedDots: Int) {
                        for (c in el.childElements()) when (c.tagName) {
                            "beam", "graceGrp", "fTrem", "bTrem" -> if (c.tagName != "graceGrp") walk(c, ratio, inheritedDur, inheritedDots)
                            "tuplet" -> {
                                val num = c.attr("num")?.toDoubleOrNull() ?: 3.0
                                val base = c.attr("numbase")?.toDoubleOrNull() ?: 2.0
                                walk(c, ratio * base / num, inheritedDur, inheritedDots)
                            }
                            "note", "chord", "rest", "space" -> {
                                if (c.attr("grace") != null) continue
                                val dur = length(c.attr("dur") ?: inheritedDur, c.attr("dots")?.toIntOrNull() ?: inheritedDots) * ratio
                                val start = t0 + cursor
                                val heads = when (c.tagName) { "note" -> listOf(c); "chord" -> c.children("note"); else -> emptyList() }
                                for (h in heads) {
                                    if (h.attr("grace") != null) continue
                                    val p = pitch(h, barAcc, key) ?: continue
                                    val tieAttr = h.attr("tie").orEmpty()
                                    val keyT = n to p
                                    val cont = openTies[keyT]
                                    val idx = if (cont != null && (tieAttr.contains('m') || tieAttr.contains('t') || kotlin.math.abs(notes[cont].end - start) < 1e-4)) {
                                        openTies.remove(keyT)
                                        notes[cont] = notes[cont].copy(duration = notes[cont].duration + dur); cont
                                    } else { notes.add(Note(p, start, dur)); notes.lastIndex }
                                    val id = h.getAttribute("xml:id")
                                    if (tieAttr.contains('i') || tieAttr.contains('m') || id in tieStarts) openTies[keyT] = idx
                                }
                                cursor += dur
                            }
                            "mRest", "mSpace", "multiRest" -> cursor += beats * 4.0 / beatType
                        }
                    }
                    walk(layer, 1.0, null, 0)
                    maxLen = maxOf(maxLen, cursor)
                }
            }
            for (tm in m.children("tempo")) tm.attr("midi.bpm")?.toDoubleOrNull()?.let { b ->
                if (bpm < 0) bpm = b else tempos.add(TempoChange(t0 + ((tm.attr("tstamp")?.toDoubleOrNull() ?: 1.0) - 1) * 4.0 / beatType, b))
            }
            val nominal = beats * 4.0 / beatType
            info.length = if (m.attr("metcon") == "false" && maxLen > 0) maxLen else maxOf(nominal, if (maxLen > nominal + 1e-6) maxLen else nominal)
            measures.add(info)
            t0 += info.length
        }

        val withNotes = notesByStaff.filterValues { it.isNotEmpty() }
        if (withNotes.isEmpty()) throw ImportException(t("Die MEI-Datei enthält keine Noten", "The MEI file contains no notes"))
        val order = staffOrder.ifEmpty { withNotes.keys.toList() }
        val all = ArrayList<Note>()
        if (withNotes.size >= 2) {
            val left = if (order.size == 2) order[1] else withNotes.minBy { (_, ns) -> ns.map { it.pitch }.average() }.key
            withNotes.forEach { (sid, ns) -> ns.forEach { all.add(it.copy(hand = if (sid == left) Hand.LEFT else Hand.RIGHT)) } }
        } else all.addAll(withNotes.values.first())

        val nominal = beats * 4.0 / beatType
        val pickup = measures.firstOrNull()?.length?.takeIf { it < nominal - 1e-6 } ?: 0.0
        val (played, playedTempos) = Repeats.expand(all, measures, tempos)
        return Song(
            id = SongUtils.newId(),
            title = title.ifBlank { fallbackTitle },
            composer = composer,
            bpm = if (bpm > 0) bpm else 100.0,
            beatsPerMeasure = beats,
            beatUnit = beatType,
            notes = SongUtils.dedupe(played),
            tempoChanges = playedTempos.filter { it.beat > 1e-6 },
            source = SongSource.MEI,
            createdAt = System.currentTimeMillis(),
            pickupBeats = pickup,
            keyFifths = key,
        )
    }

    private fun keySig(s: String?): Int {
        if (s.isNullOrBlank() || s == "0") return 0
        val n = s.takeWhile { it.isDigit() }.toIntOrNull() ?: return 0
        return if (s.endsWith("f")) -n else n
    }

    private const val SHARP_ORDER = "fcgdaeb"

    private fun accidental(a: String?): Int? = when (a) {
        "s" -> 1; "f" -> -1; "ss", "x" -> 2; "ff" -> -2; "n" -> 0; "ns" -> 1; "nf" -> -1
        else -> null
    }

    private fun pitch(note: Element, barAcc: HashMap<String, Int>, key: Int): Int? {
        val pname = note.attr("pname")?.firstOrNull()?.lowercaseChar() ?: return null
        val oct = note.attr("oct")?.toIntOrNull() ?: return null
        val id = "$pname$oct"
        val written = accidental(note.attr("accid") ?: note.first("accid")?.attr("accid"))
        val gestural = accidental(note.attr("accid.ges") ?: note.first("accid")?.attr("accid.ges"))
        val alter = when {
            gestural != null -> gestural
            written != null -> written.also { barAcc[id] = it }
            else -> barAcc[id] ?: when {
                key > 0 && SHARP_ORDER.indexOf(pname) < key -> 1
                key < 0 && SHARP_ORDER.reversed().indexOf(pname) < -key -> -1
                else -> 0
            }
        }
        if (written != null) barAcc[id] = written
        return (oct + 1) * 12 + pc.getValue(pname) + alter
    }

    private fun length(dur: String?, dots: Int): Double {
        val base = when (dur) {
            "long" -> 16.0; "breve" -> 8.0; "1" -> 4.0; "2" -> 2.0; "4" -> 1.0; "8" -> 0.5; "16" -> 0.25
            "32" -> 0.125; "64" -> 0.0625; "128" -> 0.03125
            else -> 1.0
        }
        var f = 1.0; var add = 0.5
        repeat(dots) { f += add; add /= 2 }
        return base * f
    }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { out.add(it) }
        return out
    }
    private fun Element.child(name: String): Element? = childElements().firstOrNull { it.tagName == name }
    private fun Element.children(name: String): List<Element> = childElements().filter { it.tagName == name }
    private fun Element.all(name: String): List<Element> {
        val nl = getElementsByTagName(name)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
    private fun Element.first(name: String): Element? = all(name).firstOrNull()
    private fun Element.text(): String = textContent.trim()
}
