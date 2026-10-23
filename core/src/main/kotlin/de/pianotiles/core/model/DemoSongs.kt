package de.pianotiles.core.model

/**
 * Built-in public-domain pieces so the app is usable right after installation.
 * Written in a tiny text notation: `"E5/0.25 D#5/0.25 r/0.5 C3+E3+G3/1"`
 * (pitch or chord joined with '+', '/' duration in beats, `r` = rest).
 */
object DemoSongs {

    fun all(): List<Song> = listOf(scale(), entchen(), twinkle(), haenschen(), odeToJoy(), jingleBells(), birthday(), minuet(), furElise())

    private val letters = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11)

    fun parsePitch(s: String): Int {
        val letter = letters[s[0].uppercaseChar()] ?: error("Bad pitch $s")
        var i = 1
        var alter = 0
        while (i < s.length && (s[i] == '#' || s[i] == 'b')) { alter += if (s[i] == '#') 1 else -1; i++ }
        val octave = s.substring(i).toInt()
        return (octave + 1) * 12 + letter + alter
    }

    fun seq(text: String, hand: Hand, start: Double = 0.0): List<Note> {
        val out = ArrayList<Note>()
        var t = start
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token.isEmpty() || token == "|") continue
            val (p, d) = token.split('/')
            val dur = d.toDouble()
            if (p != "r") p.split('+').forEach { out.add(Note(parsePitch(it), t, dur * 0.95, hand)) }
            t += dur
        }
        return out
    }

    private fun song(id: String, title: String, composer: String, bpm: Double, num: Int, den: Int, notes: List<Note>, pickup: Double = 0.0, sections: List<Section> = emptyList()) =
        Song(id = id, title = title, composer = composer, bpm = bpm, beatsPerMeasure = num, beatUnit = den,
            notes = notes, source = SongSource.DEMO, pickupBeats = pickup, sections = sections)

    fun scale() = song(
        "demo-scale", "C-Dur-Tonleiter (beidhändig)", "Übung", 80.0, 4, 4,
        seq("C4/1 D4/1 E4/1 F4/1 G4/1 A4/1 B4/1 C5/1 B4/1 A4/1 G4/1 F4/1 E4/1 D4/1 C4/2", Hand.RIGHT) +
            seq("C3/1 D3/1 E3/1 F3/1 G3/1 A3/1 B3/1 C4/1 B3/1 A3/1 G3/1 F3/1 E3/1 D3/1 C3/2", Hand.LEFT),
        sections = listOf(Section("Aufwärts", 0.0, 8.0), Section("Abwärts", 8.0, 16.0)),
    )

    fun entchen() = song(
        "demo-entchen", "Alle meine Entchen", "Volkslied", 100.0, 4, 4,
        seq("C4/1 D4/1 E4/1 F4/1 | G4/2 G4/2 | A4/1 A4/1 A4/1 A4/1 | G4/4 | A4/1 A4/1 A4/1 A4/1 | G4/4 | " +
            "F4/1 F4/1 F4/1 F4/1 | E4/2 E4/2 | D4/1 D4/1 D4/1 D4/1 | C4/4", Hand.RIGHT),
    )

    fun odeToJoy() = song(
        "demo-ode", "Ode an die Freude", "L. v. Beethoven", 96.0, 4, 4,
        seq("E4/1 E4/1 F4/1 G4/1 | G4/1 F4/1 E4/1 D4/1 | C4/1 C4/1 D4/1 E4/1 | E4/1.5 D4/0.5 D4/2 | " +
            "E4/1 E4/1 F4/1 G4/1 | G4/1 F4/1 E4/1 D4/1 | C4/1 C4/1 D4/1 E4/1 | D4/1.5 C4/0.5 C4/2", Hand.RIGHT) +
            seq("C3+G3/4 | G2+G3/4 | C3+G3/4 | G2+G3/4 | C3+G3/4 | G2+G3/4 | C3+G3/4 | G2+G3/2 C3+G3/2", Hand.LEFT),
        sections = listOf(Section("1. Hälfte", 0.0, 16.0), Section("2. Hälfte", 16.0, 32.0)),
    )

    fun twinkle() = song(
        "demo-twinkle", "Twinkle Twinkle Little Star", "Volkslied", 100.0, 4, 4,
        seq("C4/1 C4/1 G4/1 G4/1 | A4/1 A4/1 G4/2 | F4/1 F4/1 E4/1 E4/1 | D4/1 D4/1 C4/2 | " +
            "G4/1 G4/1 F4/1 F4/1 | E4/1 E4/1 D4/2 | G4/1 G4/1 F4/1 F4/1 | E4/1 E4/1 D4/2 | " +
            "C4/1 C4/1 G4/1 G4/1 | A4/1 A4/1 G4/2 | F4/1 F4/1 E4/1 E4/1 | D4/1 D4/1 C4/2", Hand.RIGHT) +
            seq("C3/2 E3/2 | F3/2 E3/2 | D3/2 C3/2 | G2/2 C3/2 | E3/2 D3/2 | C3/2 G2/2 | E3/2 D3/2 | C3/2 G2/2 | " +
                "C3/2 E3/2 | F3/2 E3/2 | D3/2 C3/2 | G2/2 C3/2", Hand.LEFT),
        sections = listOf(Section("Teil A", 0.0, 16.0), Section("Teil B", 16.0, 32.0), Section("Teil A'", 32.0, 48.0)),
    )

    fun haenschen() = song(
        "demo-haenschen", "Hänschen klein", "Volkslied", 100.0, 4, 4,
        seq("G4/1 E4/1 E4/2 | F4/1 D4/1 D4/2 | C4/1 D4/1 E4/1 F4/1 | G4/1 G4/1 G4/2 | " +
            "G4/1 E4/1 E4/2 | F4/1 D4/1 D4/2 | C4/1 E4/1 G4/1 G4/1 | C4/4 | " +
            "D4/1 D4/1 D4/1 D4/1 | D4/1 E4/1 F4/2 | E4/1 E4/1 E4/1 E4/1 | E4/1 F4/1 G4/2 | " +
            "G4/1 E4/1 E4/2 | F4/1 D4/1 D4/2 | C4/1 E4/1 G4/1 G4/1 | C4/4", Hand.RIGHT) +
            seq("C3+G3/4 | G2+G3/4 | C3+G3/4 | C3+E3/4 | C3+G3/4 | G2+G3/4 | C3+G3/2 G2+G3/2 | C3+E3/4 | " +
                "G2+G3/4 | G2+G3/4 | C3+G3/4 | C3+G3/4 | C3+G3/4 | G2+G3/4 | C3+G3/2 G2+G3/2 | C3+E3/4", Hand.LEFT),
    )

    fun jingleBells() = song(
        "demo-jingle", "Jingle Bells (Refrain)", "J. Pierpont", 110.0, 4, 4,
        seq("E4/1 E4/1 E4/2 | E4/1 E4/1 E4/2 | E4/1 G4/1 C4/1.5 D4/0.5 | E4/4 | " +
            "F4/1 F4/1 F4/1.5 F4/0.5 | F4/1 E4/1 E4/1 E4/0.5 E4/0.5 | E4/1 D4/1 D4/1 E4/1 | D4/2 G4/2 | " +
            "E4/1 E4/1 E4/2 | E4/1 E4/1 E4/2 | E4/1 G4/1 C4/1.5 D4/0.5 | E4/4 | " +
            "F4/1 F4/1 F4/1 F4/1 | F4/1 E4/1 E4/1 E4/0.5 E4/0.5 | G4/1 G4/1 F4/1 D4/1 | C4/4", Hand.RIGHT) +
            seq("C3+G3/4 | C3+G3/4 | C3+G3/4 | C3+G3/4 | F2+F3/4 | C3+G3/4 | D3+F#3/4 | G2+G3/4 | " +
                "C3+G3/4 | C3+G3/4 | C3+G3/4 | C3+G3/4 | F2+F3/4 | C3+G3/4 | G2+F3/4 | C3+E3/4", Hand.LEFT),
    )

    fun birthday() = song(
        "demo-birthday", "Happy Birthday", "M. & P. Hill", 90.0, 3, 4,
        seq("G4/0.75 G4/0.25 | A4/1 G4/1 C5/1 | B4/2 G4/0.75 G4/0.25 | A4/1 G4/1 D5/1 | C5/2 G4/0.75 G4/0.25 | " +
            "G5/1 E5/1 C5/1 | B4/1 A4/1 F5/0.75 F5/0.25 | E5/1 C5/1 D5/1 | C5/3", Hand.RIGHT) +
            seq("r/1 | C3+G3/3 | G2+G3/3 | G2+F3/3 | C3+E3/3 | C3+E3/3 | F2+F3/3 | G2+G3/3 | C3+E3/3", Hand.LEFT),
        pickup = 1.0,
    )

    fun minuet() = song(
        "demo-minuet", "Menuett in G", "C. Petzold", 100.0, 3, 4,
        seq("D5/1 G4/0.5 A4/0.5 B4/0.5 C5/0.5 | D5/1 G4/1 G4/1 | E5/1 C5/0.5 D5/0.5 E5/0.5 F#5/0.5 | G5/1 G4/1 G4/1 | " +
            "C5/1 D5/0.5 C5/0.5 B4/0.5 A4/0.5 | B4/1 C5/0.5 B4/0.5 A4/0.5 G4/0.5 | F#4/1 G4/0.5 A4/0.5 B4/0.5 G4/0.5 | A4/3 | " +
            "D5/1 G4/0.5 A4/0.5 B4/0.5 C5/0.5 | D5/1 G4/1 G4/1 | E5/1 C5/0.5 D5/0.5 E5/0.5 F#5/0.5 | G5/1 G4/1 G4/1 | " +
            "C5/1 D5/0.5 C5/0.5 B4/0.5 A4/0.5 | B4/1 C5/0.5 B4/0.5 A4/0.5 G4/0.5 | A4/1 B4/0.5 A4/0.5 G4/0.5 F#4/0.5 | G4/3", Hand.RIGHT) +
            seq("G3+B3/2 A3/1 | B3/3 | C4/3 | B3/3 | A3/3 | G3/3 | D4/1 B3/1 G3/1 | D4/1 D3/1 C4/1 | " +
                "B3/2 A3/1 | B3/1 G3/2 | C4/3 | B3/1 C4/1 B3/0.5 A3/0.5 | G3/2 B3/1 | C4/2 B3/1 | A3/1 D3/1 F#3/1 | G3/2 G2/1", Hand.LEFT),
        sections = listOf(Section("1. Hälfte", 0.0, 24.0), Section("2. Hälfte", 24.0, 48.0)),
    ).copy(keyFifths = 1)

    fun furElise(): Song {
        val s = 0.25 // sixteenth
        val rh = seq(
            "E5/$s D#5/$s | E5/$s D#5/$s E5/$s B4/$s D5/$s C5/$s | A4/0.5 r/$s C4/$s E4/$s A4/$s | " +
                "B4/0.5 r/$s E4/$s G#4/$s B4/$s | C5/0.5 r/$s E4/$s E5/$s D#5/$s | " +
                "E5/$s D#5/$s E5/$s B4/$s D5/$s C5/$s | A4/0.5 r/$s C4/$s E4/$s A4/$s | " +
                "B4/0.5 r/$s E4/$s C5/$s B4/$s | A4/1.5", Hand.RIGHT,
        )
        val lh = seq(
            "r/0.5 | r/1.5 | A2/$s E3/$s A3/$s r/0.75 | E2/$s E3/$s G#3/$s r/0.75 | A2/$s E3/$s A3/$s r/0.75 | " +
                "r/1.5 | A2/$s E3/$s A3/$s r/0.75 | E2/$s E3/$s G#3/$s r/0.75 | A2/$s E3/$s A3/$s", Hand.LEFT,
        )
        return song("demo-elise", "Für Elise (Anfang)", "L. v. Beethoven", 60.0, 3, 8, rh + lh, pickup = 0.5)
    }
}
