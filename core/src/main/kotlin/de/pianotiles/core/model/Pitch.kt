package de.pianotiles.core.model

import de.pianotiles.core.i18n.CoreText.t
import kotlin.math.abs
import kotlin.math.pow

enum class NoteNaming { GERMAN, ENGLISH, SOLFEGE, NONE }

object Pitch {
    const val LOWEST_PIANO = 21   // A0
    const val HIGHEST_PIANO = 108 // C8

    private val germanNames = arrayOf("C", "Cis", "D", "Dis", "E", "F", "Fis", "G", "Gis", "A", "B", "H")
    private val englishNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val solfegeNames = arrayOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")
    private val germanFlatNames = arrayOf("C", "Des", "D", "Es", "E", "F", "Ges", "G", "As", "A", "B", "H")
    private val englishFlatNames = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    private val solfegeFlatNames = arrayOf("Do", "Re♭", "Re", "Mi♭", "Mi", "Fa", "Sol♭", "Sol", "La♭", "La", "Si♭", "Si")

    fun isBlackKey(pitch: Int): Boolean = when (((pitch % 12) + 12) % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    fun frequency(pitch: Int): Double = 440.0 * 2.0.pow((pitch - 69) / 12.0)

    fun octave(pitch: Int): Int = pitch / 12 - 1

    /** Short name without octave (e.g. "Fis", or "Ges" with [flats]), used on tiles. */
    fun name(pitch: Int, naming: NoteNaming, flats: Boolean = false): String {
        val pc = ((pitch % 12) + 12) % 12
        return when (naming) {
            NoteNaming.GERMAN -> (if (flats) germanFlatNames else germanNames)[pc]
            NoteNaming.ENGLISH -> (if (flats) englishFlatNames else englishNames)[pc]
            NoteNaming.SOLFEGE -> (if (flats) solfegeFlatNames else solfegeNames)[pc]
            NoteNaming.NONE -> ""
        }
    }

    /** Name including octave in scientific notation, e.g. "C4". */
    fun fullName(pitch: Int, naming: NoteNaming = NoteNaming.ENGLISH, flats: Boolean = false): String =
        name(pitch, if (naming == NoteNaming.NONE) NoteNaming.ENGLISH else naming, flats) + octave(pitch)

    private val majorScale = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /**
     * Key signature (-6..6) whose major scale contains the most of [pitches]; ties go to the key with
     * fewer accidentals. Songs don't store their key, this is enough to spell black keys sensibly.
     */
    fun estimateKeyFifths(pitches: Iterable<Int>): Int {
        val counts = IntArray(12)
        for (p in pitches) counts[((p % 12) + 12) % 12]++
        return (-6..6).sortedBy { abs(it) }.maxBy { fifths ->
            val tonic = Math.floorMod(fifths * 7, 12)
            majorScale.sumOf { counts[(tonic + it) % 12] }
        }
    }

    /** Whether black keys should be named as flats (E♭) instead of sharps (D#) for these notes. */
    fun usesFlats(pitches: Iterable<Int>): Boolean = estimateKeyFifths(pitches) < 0

    /** Diatonic step index: C4 = 28 (7 * 4), D4 = 29 ... Only for natural note letters. */
    private val letterSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /**
     * Converts a diatonic position (steps of a staff: line, space, line...) to a MIDI pitch.
     * [diatonic] counts letters from C0 (C0 = 0, D0 = 1, ..., C4 = 28).
     * [keyFifths] is the key signature: positive = number of sharps, negative = number of flats.
     */
    fun fromDiatonic(diatonic: Int, keyFifths: Int = 0, accidental: Int? = null): Int {
        val octave = Math.floorDiv(diatonic, 7)
        val letter = Math.floorMod(diatonic, 7)
        val base = (octave + 1) * 12 + letterSemitones[letter]
        val alter = accidental ?: keyAlteration(letter, keyFifths)
        return base + alter
    }

    /** Letter indices (C=0 ... B=6) in order of sharps: F C G D A E B. */
    private val sharpOrder = intArrayOf(3, 0, 4, 1, 5, 2, 6)
    /** Order of flats: B E A D G C F. */
    private val flatOrder = intArrayOf(6, 2, 5, 1, 4, 0, 3)

    fun keyAlteration(letter: Int, keyFifths: Int): Int {
        if (keyFifths > 0) {
            for (i in 0 until minOf(keyFifths, 7)) if (sharpOrder[i] == letter) return 1
        } else if (keyFifths < 0) {
            for (i in 0 until minOf(-keyFifths, 7)) if (flatOrder[i] == letter) return -1
        }
        return 0
    }

    fun keyName(keyFifths: Int): String = when (keyFifths) {
        0 -> t("C-Dur / a-Moll", "C major / A minor")
        1 -> t("G-Dur / e-Moll", "G major / E minor")
        2 -> t("D-Dur / h-Moll", "D major / B minor")
        3 -> t("A-Dur / fis-Moll", "A major / F♯ minor")
        4 -> t("E-Dur / cis-Moll", "E major / C♯ minor")
        5 -> t("H-Dur / gis-Moll", "B major / G♯ minor")
        6 -> t("Fis-Dur / dis-Moll", "F♯ major / D♯ minor")
        7 -> t("Cis-Dur / ais-Moll", "C♯ major / A♯ minor")
        -1 -> t("F-Dur / d-Moll", "F major / D minor")
        -2 -> t("B-Dur / g-Moll", "B♭ major / G minor")
        -3 -> t("Es-Dur / c-Moll", "E♭ major / C minor")
        -4 -> t("As-Dur / f-Moll", "A♭ major / F minor")
        -5 -> t("Des-Dur / b-Moll", "D♭ major / B♭ minor")
        -6 -> t("Ges-Dur / es-Moll", "G♭ major / E♭ minor")
        -7 -> t("Ces-Dur / as-Moll", "C♭ major / A♭ minor")
        else -> "?"
    }
}
