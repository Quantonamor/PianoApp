package de.pianotiles.core.practice

import de.pianotiles.core.i18n.CoreText
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource

/**
 * One step of the learning path: a piece with the settings to practise it (hands, mode, tempo) and the accuracy
 * that counts as passed.
 */
data class Lesson(
    val id: String,
    val songId: String,
    private val titleDe: String,
    private val titleEn: String,
    private val tipDe: String,
    private val tipEn: String,
    val hands: HandSelection,
    val mode: PracticeMode = PracticeMode.WAIT,
    /** Tempo factor (1 = as written). */
    val speed: Float = 1f,
    /** Accuracy in percent for the first star. */
    val goal: Int = 80,
) {
    val title get() = CoreText.t(titleDe, titleEn)
    val tip get() = CoreText.t(tipDe, tipEn)

    /** Stars for an accuracy: 1 at the goal, 2 at goal + 10 (at most 95), 3 from 98 %. */
    fun stars(accuracy: Int): Int = when {
        accuracy >= 98 -> 3
        accuracy >= minOf(95, goal + 10) -> 2
        accuracy >= goal -> 1
        else -> 0
    }
}

/**
 * The learning path for beginners: from single fingers of one hand to short pieces with both hands. Every lesson
 * can be opened at any time; the next one not yet passed is suggested.
 */
object Course {

    val lessons: List<Lesson> = listOf(
        Lesson("l01", "ex-five-right", "Fünf Finger, rechte Hand", "Five fingers, right hand",
            "Daumen auf das C in der Mitte, jeder Finger hat seine Taste: C D E F G und zurück.",
            "Thumb on middle C, each finger has its own key: C D E F G and back.", HandSelection.RIGHT),
        Lesson("l02", "ex-five-left", "Fünf Finger, linke Hand", "Five fingers, left hand",
            "Kleiner Finger auf das C eine Oktave tiefer: C D E F G und zurück.",
            "Little finger on the C an octave lower: C D E F G and back.", HandSelection.LEFT),
        Lesson("l03", "demo-entchen", "Alle meine Entchen", "All my little ducklings",
            "Das erste Lied – nur rechte Hand, die Noten warten auf dich.",
            "Your first song – right hand only, the notes wait for you.", HandSelection.RIGHT),
        Lesson("l04", "demo-entchen", "Alle meine Entchen im Takt", "All my little ducklings in time",
            "Jetzt laufen die Noten durch – langsam, im gleichmäßigen Tempo.",
            "Now the notes keep moving – slowly, in an even tempo.", HandSelection.RIGHT, PracticeMode.FLOW, 0.7f),
        Lesson("l05", "demo-haenschen", "Hänschen klein", "Little Hans",
            "Größere Schritte für die rechte Hand: G E E, F D D.", "Bigger steps for the right hand: G E E, F D D.",
            HandSelection.RIGHT),
        Lesson("l06", "ex-hands-together", "Beide Hände zusammen", "Both hands together",
            "Gleiche Töne, beide Hände gleichzeitig – erst langsam.", "The same notes with both hands at once – slowly at first.",
            HandSelection.BOTH),
        Lesson("l07", "demo-ode", "Ode an die Freude", "Ode to Joy",
            "Die rechte Hand bleibt in ihrer Fünf-Finger-Lage.", "The right hand stays in its five-finger position.",
            HandSelection.RIGHT),
        Lesson("l08", "demo-ode", "Ode an die Freude mit Begleitung", "Ode to Joy with accompaniment",
            "Die linke Hand spielt lange Begleittöne dazu.", "The left hand adds long accompanying notes.", HandSelection.BOTH),
        Lesson("l09", "demo-twinkle", "Twinkle Twinkle Little Star", "Twinkle Twinkle Little Star",
            "Melodie rechts, Begleitung links.", "Melody right, accompaniment left.", HandSelection.BOTH),
        Lesson("l10", "demo-scale", "C-Dur-Tonleiter", "C major scale",
            "Der Daumen geht unter dem 3. Finger durch – die eingeblendeten Hände zeigen, wie.",
            "The thumb passes under the 3rd finger – the hands on screen show how.", HandSelection.RIGHT),
        Lesson("l11", "demo-jingle", "Jingle Bells", "Jingle Bells",
            "Ein schnelleres Lied mit beiden Händen.", "A faster song with both hands.", HandSelection.BOTH, PracticeMode.WAIT, 0.8f),
        Lesson("l12", "demo-minuet", "Menuett in G", "Minuet in G",
            "Ein klassisches Stück im Dreiertakt – zuerst die rechte Hand.", "A classical piece in triple time – right hand first.",
            HandSelection.RIGHT),
        Lesson("l13", "demo-elise", "Für Elise (Anfang)", "Für Elise (opening)",
            "Das berühmte Thema – der Wechsel E–Dis mit zwei Fingern.", "The famous theme – E–D♯ alternating with two fingers.",
            HandSelection.BOTH),
    )

    fun lesson(id: String): Lesson? = lessons.firstOrNull { it.id == id }

    /** The first lesson not passed yet (or the last one when all are done). */
    fun next(stars: (String) -> Int): Lesson = lessons.firstOrNull { stars(it.id) == 0 } ?: lessons.last()

    /** Exercise pieces of the course that are not demo songs. */
    fun exerciseSongs(): List<Song> = listOf(
        exercise("ex-five-right", CoreText.t("Übung: Fünf Finger rechts", "Exercise: five fingers right"),
            DemoSongs.seq("C4/1 D4/1 E4/1 F4/1 | G4/1 F4/1 E4/1 D4/1 | C4/1 E4/1 D4/1 F4/1 | E4/1 D4/1 C4/2", Hand.RIGHT)),
        exercise("ex-five-left", CoreText.t("Übung: Fünf Finger links", "Exercise: five fingers left"),
            DemoSongs.seq("C3/1 D3/1 E3/1 F3/1 | G3/1 F3/1 E3/1 D3/1 | C3/1 E3/1 D3/1 F3/1 | E3/1 D3/1 C3/2", Hand.LEFT)),
        exercise("ex-hands-together", CoreText.t("Übung: Beide Hände", "Exercise: both hands"),
            DemoSongs.seq("C4/2 D4/2 | E4/2 D4/2 | C4/1 D4/1 E4/1 F4/1 | G4/4", Hand.RIGHT) +
                DemoSongs.seq("C3/2 D3/2 | E3/2 D3/2 | C3/1 D3/1 E3/1 F3/1 | G3/4", Hand.LEFT)),
    )

    private fun exercise(id: String, title: String, notes: List<de.pianotiles.core.model.Note>) =
        Song(id = id, title = title, composer = CoreText.t("Lernpfad", "Learning path"), bpm = 80.0, beatsPerMeasure = 4, beatUnit = 4,
            notes = notes, source = SongSource.DEMO)
}
