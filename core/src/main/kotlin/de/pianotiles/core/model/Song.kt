package de.pianotiles.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor

/** Which hand plays a note. Used for colouring and for practising hands separately. */
@Serializable
enum class Hand { LEFT, RIGHT }

/**
 * A single note.
 *
 * All musical times are measured in **beats** (quarter notes), independent of tempo.
 * @param pitch MIDI note number (60 = middle C / c').
 */
@Serializable
data class Note(
    val pitch: Int,
    val start: Double,
    val duration: Double,
    val hand: Hand = Hand.RIGHT,
    val velocity: Int = 80,
) {
    val end: Double get() = start + duration
}

/** Tempo from [beat] on (quarter notes per minute). */
@Serializable
data class TempoChange(val beat: Double, val bpm: Double)

/** A named range of the piece that can be practised as a loop ("Teil A", "Refrain", ...). */
@Serializable
data class Section(
    val name: String,
    val startBeat: Double,
    val endBeat: Double,
)

@Serializable
enum class SongSource { DEMO, MIDI, MUSICXML, PDF, MANUAL }

/** Practice statistics stored per song. */
@Serializable
data class SongStats(
    val bestAccuracy: Int = 0,
    val practiceSeconds: Long = 0,
    val lastPracticedAt: Long = 0,
    /** Number of mistakes per measure index; used to suggest "difficult measures". */
    val mistakesPerMeasure: Map<Int, Int> = emptyMap(),
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val composer: String = "",
    val bpm: Double = 100.0,
    val beatsPerMeasure: Int = 4,
    val beatUnit: Int = 4,
    val notes: List<Note>,
    val sections: List<Section> = emptyList(),
    val source: SongSource = SongSource.MANUAL,
    val createdAt: Long = 0,
    val favorite: Boolean = false,
    val stats: SongStats = SongStats(),
    /** Beat offset of the first bar line (for pick-up measures / Auftakt). */
    val pickupBeats: Double = 0.0,
    /** Tempo changes after the start ([bpm] is the initial tempo). */
    val tempoChanges: List<TempoChange> = emptyList(),
) {
    /** Tempo at [beat], following [tempoChanges]. */
    fun bpmAt(beat: Double): Double = tempoChanges.lastOrNull { it.beat <= beat + 1e-9 }?.bpm ?: bpm

    /** Length of one measure in quarter-note beats (6/8 → 3.0). */
    val measureLength: Double get() = beatsPerMeasure * 4.0 / beatUnit

    val lengthBeats: Double get() = notes.maxOfOrNull { it.end } ?: 0.0

    val measureCount: Int
        get() = if (notes.isEmpty()) 1 else measureIndexAt(lengthBeats - 1e-6) + 1

    fun measureIndexAt(beat: Double): Int {
        val b = beat - pickupBeats
        if (b < 0) return 0
        return floor(b / measureLength + 1e-9).toInt() + if (pickupBeats > 0) 1 else 0
    }

    /** Start beat of measure [index] (0-based). A pick-up measure is measure 0. */
    fun measureStart(index: Int): Double {
        if (pickupBeats > 0) {
            return if (index <= 0) 0.0 else pickupBeats + (index - 1) * measureLength
        }
        return index * measureLength
    }

    fun measureEnd(index: Int): Double = measureStart(index + 1)

    val lowestPitch: Int get() = notes.minOfOrNull { it.pitch } ?: 60
    val highestPitch: Int get() = notes.maxOfOrNull { it.pitch } ?: 72

    fun secondsPerBeat(speed: Double = 1.0): Double = 60.0 / (bpm * speed)

    /** Rough difficulty 1..5 based on note density and range. */
    val difficulty: Int
        get() {
            if (notes.isEmpty()) return 1
            val notesPerSecond = notes.size / (lengthBeats * secondsPerBeat()).coerceAtLeast(1.0)
            val chordy = notes.groupBy { (it.start * 1000).toLong() }.count { it.value.size > 1 }.toDouble() /
                notes.size.coerceAtLeast(1)
            val score = notesPerSecond * 0.9 + chordy * 2 + (highestPitch - lowestPitch) / 24.0
            return score.coerceIn(1.0, 5.0).toInt()
        }
}

/** Notes that start at (nearly) the same time are grouped into one [Step] (a chord). */
data class Step(
    val index: Int,
    val start: Double,
    val notes: List<Note>,
) {
    val pitches: Set<Int> get() = notes.mapTo(HashSet()) { it.pitch }
}

fun List<Note>.toSteps(tolerance: Double = 0.02): List<Step> {
    val sorted = sortedWith(compareBy({ it.start }, { it.pitch }))
    val steps = ArrayList<Step>()
    var current = ArrayList<Note>()
    var currentStart = Double.NaN
    for (n in sorted) {
        if (current.isEmpty() || abs(n.start - currentStart) <= tolerance) {
            if (current.isEmpty()) currentStart = n.start
            current.add(n)
        } else {
            steps.add(Step(steps.size, currentStart, current))
            current = arrayListOf(n)
            currentStart = n.start
        }
    }
    if (current.isNotEmpty()) steps.add(Step(steps.size, currentStart, current))
    return steps
}
