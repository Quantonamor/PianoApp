package de.pianotiles.core

import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.SongJson
import de.pianotiles.core.model.toSteps
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun jsonRoundTrip() {
        for (s in DemoSongs.all()) assertEquals(s, SongJson.decode(SongJson.encode(s)))
    }

    @Test
    fun pitchHelpers() {
        assertEquals(60, Pitch.fromDiatonic(28))          // C4
        assertEquals(66, Pitch.fromDiatonic(31, 1))       // F#4 in G major
        assertEquals(70, Pitch.fromDiatonic(34, -1))      // Bb4 in F major
        assertEquals(71, Pitch.fromDiatonic(34, -1, 0))   // natural sign
        assertEquals("Fis", Pitch.name(66, de.pianotiles.core.model.NoteNaming.GERMAN))
        assertEquals("H", Pitch.name(71, de.pianotiles.core.model.NoteNaming.GERMAN))
        assertEquals(440.0, Pitch.frequency(69), 1e-9)
    }

    @Test
    fun stepsGroupChords() {
        val steps = DemoSongs.odeToJoy().notes.toSteps()
        assertEquals(setOf(48, 55, 64), steps.first().pitches)
    }

    @Test
    fun midiExportRoundTrip() {
        val song = DemoSongs.furElise()
        val back = de.pianotiles.core.io.MidiImporter.import(de.pianotiles.core.io.MidiExporter.export(song), "x")
        assertEquals(song.notes.size, back.notes.size)
        assertEquals(song.bpm, back.bpm, 0.5)
        for ((a, b) in song.notes.sortedWith(compareBy({ it.start }, { it.pitch })).zip(back.notes.sortedWith(compareBy({ it.start }, { it.pitch })))) {
            assertEquals(a.pitch, b.pitch); assertEquals(a.start, b.start, 0.01); assertEquals(a.hand, b.hand)
        }
    }
}
