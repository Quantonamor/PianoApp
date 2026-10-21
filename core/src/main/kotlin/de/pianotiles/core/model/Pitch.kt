package de.pianotiles.core.model

import kotlin.math.pow

enum class NoteNaming { GERMAN, ENGLISH, SOLFEGE, NONE }

object Pitch {
    const val LOWEST_PIANO = 21   // A0
    const val HIGHEST_PIANO = 108 // C8

    private val germanNames = arrayOf("C", "Cis", "D", "Dis", "E", "F", "Fis", "G", "Gis", "A", "B", "H")
    private val englishNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val solfegeNames = arrayOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

    fun isBlackKey(pitch: Int): Boolean = when (((pitch % 12) + 12) % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    fun frequency(pitch: Int): Double = 440.0 * 2.0.pow((pitch - 69) / 12.0)

    fun octave(pitch: Int): Int = pitch / 12 - 1

    /** Short name without octave (e.g. "Fis"), used on tiles. */
    fun name(pitch: Int, naming: NoteNaming): String {
        val pc = ((pitch % 12) + 12) % 12
        return when (naming) {
            NoteNaming.GERMAN -> germanNames[pc]
            NoteNaming.ENGLISH -> englishNames[pc]
            NoteNaming.SOLFEGE -> solfegeNames[pc]
            NoteNaming.NONE -> ""
        }
    }

    /** Name including octave in scientific notation, e.g. "C4". */
    fun fullName(pitch: Int, naming: NoteNaming = NoteNaming.ENGLISH): String =
        name(pitch, if (naming == NoteNaming.NONE) NoteNaming.ENGLISH else naming) + octave(pitch)

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
        0 -> "C-Dur / a-Moll"
        1 -> "G-Dur / e-Moll"
        2 -> "D-Dur / h-Moll"
        3 -> "A-Dur / fis-Moll"
        4 -> "E-Dur / cis-Moll"
        5 -> "H-Dur / gis-Moll"
        6 -> "Fis-Dur / dis-Moll"
        7 -> "Cis-Dur / ais-Moll"
        -1 -> "F-Dur / d-Moll"
        -2 -> "B-Dur / g-Moll"
        -3 -> "Es-Dur / c-Moll"
        -4 -> "As-Dur / f-Moll"
        -5 -> "Des-Dur / b-Moll"
        -6 -> "Ges-Dur / es-Moll"
        -7 -> "Ces-Dur / as-Moll"
        else -> "?"
    }
}
