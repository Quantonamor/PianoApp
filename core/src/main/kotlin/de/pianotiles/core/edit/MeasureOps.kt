package de.pianotiles.core.edit

import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.toSteps
import kotlin.math.abs

/** A note relative to the start of its measure (used for copy/paste and AI corrections). */
data class RelNote(val pitch: Int, val start: Double, val duration: Double, val hand: Hand)

/** Why a measure should be checked by the user ("Bitte prüfen"). */
data class MeasureIssue(val measure: Int, val reasons: List<String>)

/**
 * Measure-level editing used by the editor and by "KI-nachbessern". All functions are pure: they
 * return a new [Song]. Measure indices are 0-based (a pick-up measure is measure 0).
 */
object MeasureOps {
    private const val EPS = 1e-6

    fun lengthOf(song: Song, m: Int): Double = song.measureEnd(m) - song.measureStart(m)

    /** Notes that start inside measure [m] (optionally only one hand). */
    fun notesIn(song: Song, m: Int, hand: Hand? = null): List<Note> {
        val s = song.measureStart(m) - EPS
        val e = song.measureEnd(m) - EPS
        return song.notes.filter { it.start >= s && it.start < e && (hand == null || it.hand == hand) }
    }

    fun copy(song: Song, m: Int): List<RelNote> {
        val s = song.measureStart(m)
        return notesIn(song, m).map { RelNote(it.pitch, it.start - s, it.duration, it.hand) }
    }

    /** Replaces the content of measure [m] (only the given [hand], or both if null) with [notes]. */
    fun replace(song: Song, m: Int, notes: List<RelNote>, hand: Hand? = null): Song {
        val s = song.measureStart(m)
        val old = notesIn(song, m, hand).toSet()
        val keep = song.notes.filter { it !in old }
        val add = notes.filter { hand == null || it.hand == hand }
            .map { Note(it.pitch, s + it.start, it.duration, it.hand) }
        return song.copy(notes = (keep + add).sortedWith(compareBy({ it.start }, { it.pitch })))
    }

    /** Inserts an empty measure before [m]; everything from there on moves one measure later. */
    fun insertBefore(song: Song, m: Int): Song {
        val s = song.measureStart(m) - EPS
        val len = if (song.pickupBeats > 0 && m == 0) song.measureLength else lengthOf(song, m)
        return song.copy(
            notes = song.notes.map { if (it.start >= s) it.copy(start = it.start + len) else it },
            sheetMeasures = song.sheetMeasures.map { if (it.measure >= m) it.copy(measure = it.measure + 1) else it },
            flaggedMeasures = song.flaggedMeasures.map { if (it >= m) it + 1 else it },
        )
    }

    /** Removes measure [m]; later notes move one measure earlier. */
    fun delete(song: Song, m: Int): Song {
        val s = song.measureStart(m) - EPS
        val e = song.measureEnd(m) - EPS
        val len = lengthOf(song, m)
        val notes = song.notes.filter { it.start < s || it.start >= e }
            .map { if (it.start >= e) it.copy(start = it.start - len) else it }
        val pickup = if (m == 0 && song.pickupBeats > 0) 0.0 else song.pickupBeats
        return song.copy(
            notes = notes,
            pickupBeats = pickup,
            sheetMeasures = song.sheetMeasures.filter { it.measure != m }.map { if (it.measure > m) it.copy(measure = it.measure - 1) else it },
            flaggedMeasures = song.flaggedMeasures.filter { it != m }.map { if (it > m) it - 1 else it },
        )
    }

    fun swapHands(song: Song, m: Int): Song {
        val inM = notesIn(song, m).toSet()
        return song.copy(notes = song.notes.map { if (it in inM) it.copy(hand = if (it.hand == Hand.LEFT) Hand.RIGHT else Hand.LEFT) else it })
    }

    fun transpose(song: Song, m: Int, semitones: Int): Song {
        val inM = notesIn(song, m).toSet()
        return song.copy(notes = song.notes.map { if (it in inM) it.copy(pitch = (it.pitch + semitones).coerceIn(21, 108)) else it })
    }

    /**
     * Measures that look wrong: rhythm estimated by the recognition, a hand whose notes fill the
     * measure much less than usual in this piece / run over the bar line, or unusually large jumps.
     */
    fun issues(song: Song): List<MeasureIssue> {
        val out = ArrayList<MeasureIssue>()
        val last = song.measureCount - 1
        // Rests are normal (e.g. a left hand that only plays on the first beats): a measure is only
        // suspicious when it is much emptier than the hand's typical measure in this piece.
        val fill = HashMap<Pair<Int, Hand>, Double>()
        for (m in 1 until last) for (hand in Hand.entries) {
            val ns = notesIn(song, m, hand)
            if (ns.isNotEmpty()) fill[m to hand] = coverage(ns, song.measureStart(m), lengthOf(song, m)) / lengthOf(song, m)
        }
        val typicalFill = Hand.entries.associateWith { hand ->
            fill.filterKeys { it.second == hand }.values.sorted().let { if (it.isEmpty()) 1.0 else it[it.size / 2] }
        }
        for (m in 0..last) {
            val reasons = ArrayList<String>()
            if (m in song.flaggedMeasures) reasons.add(t("Rhythmus wurde geschätzt", "Rhythm was estimated"))
            val len = lengthOf(song, m)
            val start = song.measureStart(m)
            for (hand in Hand.entries) {
                val ns = notesIn(song, m, hand)
                if (ns.isEmpty()) continue
                val name = if (hand == Hand.RIGHT) t("rechte Hand", "right hand") else t("linke Hand", "left hand")
                val steps = ns.toSteps()
                val filled = fill[m to hand]
                if (filled != null && filled < 0.55 && filled < typicalFill.getValue(hand) * 0.6) reasons.add(t("$name: Takt nicht gefüllt (Pausen oder fehlende Noten?)", "$name: measure not filled (rests or missing notes?)"))
                val over = ns.maxOf { it.end } - (start + len)
                if (over > 0.3 && ns.none { abs(it.end - (start + len)) < 0.1 }) reasons.add(t("$name: Noten ragen über den Taktstrich", "$name: notes extend past the bar line"))
                val pitches = steps.map { st -> st.notes.sumOf { it.pitch } / st.notes.size }
                if (pitches.zipWithNext().any { (a, b) -> abs(a - b) > 19 }) reasons.add(t("$name: ungewöhnlich großer Sprung", "$name: unusually large leap"))
            }
            if (reasons.isNotEmpty()) out.add(MeasureIssue(m, reasons))
        }
        return out
    }

    /** Time in the measure during which the hand plays something. */
    private fun coverage(ns: List<Note>, start: Double, len: Double): Double {
        val steps = ns.toSteps()
        var covered = 0.0
        for ((i, st) in steps.withIndex()) {
            val next = steps.getOrNull(i + 1)?.start ?: (start + len)
            val end = minOf(st.notes.maxOf { it.end }, next, start + len)
            covered += (end - st.start).coerceAtLeast(0.0)
        }
        return covered
    }
}
