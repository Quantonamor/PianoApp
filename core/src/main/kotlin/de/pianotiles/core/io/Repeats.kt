package de.pianotiles.core.io

import de.pianotiles.core.model.Note
import de.pianotiles.core.model.TempoChange

/** Measure structure of a written score, used to play repeats and 1st/2nd endings in order. */
class WrittenMeasure(val start: Double, var length: Double = 0.0) {
    var forward = false
    var backwardTimes = 0
    val endings = HashSet<Int>()
}

internal object Repeats {
    /** Unfolds repeats: notes and tempo changes in playing order. Without repeats the input is returned. */
    fun expand(notes: List<Note>, measures: List<WrittenMeasure>, tempos: List<TempoChange>): Pair<List<Note>, List<TempoChange>> {
        if (measures.none { it.backwardTimes > 0 }) return notes to tempos
        val order = MusicXmlImporter.playOrder(measures.map { it.forward }, measures.map { it.backwardTimes }, measures.map { it.endings })
        fun measureOf(beat: Double) = measures.indexOfLast { it.start <= beat + 1e-6 }.coerceAtLeast(0)
        val byMeasure = notes.groupBy { measureOf(it.start) }
        val temposByMeasure = tempos.groupBy { measureOf(it.beat) }
        val out = ArrayList<Note>()
        val outTempos = ArrayList<TempoChange>()
        var t = 0.0
        for (m in order) {
            val info = measures[m]
            byMeasure[m]?.forEach { out.add(it.copy(start = t + it.start - info.start)) }
            temposByMeasure[m]?.forEach { outTempos.add(it.copy(beat = t + it.beat - info.start)) }
            t += info.length
        }
        return out to outTempos
    }
}
