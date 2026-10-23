package de.pianotiles.core

import de.pianotiles.core.io.MidiExporter
import de.pianotiles.core.io.MidiImporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.PedalSpan
import de.pianotiles.core.model.Song
import de.pianotiles.core.practice.EngineEvent
import de.pianotiles.core.practice.PracticeEngine
import de.pianotiles.core.practice.PracticeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PedalTest {
    private val song = Song(
        id = "p", title = "Pedal", bpm = 60.0,
        notes = listOf(Note(48, 0.0, 1.0), Note(52, 1.0, 1.0), Note(55, 2.0, 1.0), Note(60, 4.0, 1.0)),
        pedal = listOf(PedalSpan(0.0, 3.5)),
    )

    @Test
    fun notesReleasedWithThePedalDownKeepSounding() {
        assertEquals(3.5, song.soundingEnd(song.notes[0]))
        assertEquals(3.5, song.soundingEnd(song.notes[2]))
        assertEquals(5.0, song.soundingEnd(song.notes[3]))
        // the engine hands the sustained length to the synthesizer
        val e = PracticeEngine(song).apply { countIn = false; mode = PracticeMode.LISTEN; seek(0.0); play() }
        val first = e.update(0.0).filterIsInstance<EngineEvent.PlayNote>().first { it.pitch == 48 }
        assertEquals(3.5, first.durationSeconds, 1e-6)
    }

    @Test
    fun midiRoundTrip() {
        val back = MidiImporter.import(MidiExporter.export(song), "x")
        assertEquals(listOf(PedalSpan(0.0, 3.5)), back.pedal)
    }

    @Test
    fun musicXmlPedalMarks() {
        val xml = """<?xml version="1.0"?><score-partwise><part-list><score-part id="P1"><part-name>P</part-name></score-part></part-list>
            <part id="P1"><measure number="1"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
            <direction><direction-type><pedal type="start"/></direction-type></direction>
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration></note>
            <direction><direction-type><pedal type="change"/></direction-type></direction>
            <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration></note>
            <note><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration></note>
            <direction><direction-type><pedal type="stop"/></direction-type></direction>
            </measure></part></score-partwise>"""
        val s = MusicXmlImporter.import(xml.toByteArray(), "x")
        assertEquals(listOf(PedalSpan(0.0, 2.0), PedalSpan(2.0, 4.0)), s.pedal)
    }

    @Test
    fun musicXmlDynamicsBecomeVelocities() {
        val xml = """<?xml version="1.0"?><score-partwise><part-list><score-part id="P1"><part-name>P</part-name></score-part></part-list>
            <part id="P1"><measure number="1"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
            <direction><direction-type><dynamics><p/></dynamics></direction-type></direction>
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>
            <note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration></note>
            <direction><direction-type><dynamics><ff/></dynamics></direction-type></direction>
            <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration></note>
            <direction><direction-type><dynamics><sfz/></dynamics></direction-type></direction>
            <note><pitch><step>F</step><octave>4</octave></pitch><duration>1</duration></note>
            </measure></part></score-partwise>"""
        val v = MusicXmlImporter.import(xml.toByteArray(), "x").notes.sortedBy { it.start }.map { it.velocity }
        assertEquals(listOf(50, 50, 110, 127), v)
    }
}
