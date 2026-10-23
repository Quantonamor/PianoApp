package de.pianotiles.core

import de.pianotiles.core.io.MusicXmlExporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.library.SongPackage
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.FingeringSource
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.TempoChange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackageTest {

    private fun sameNotes(a: List<Note>, b: List<Note>) {
        fun key(n: Note) = listOf(n.pitch, Math.round(n.start * 48), Math.round(n.duration * 48), n.hand.ordinal)
        assertEquals(a.map(::key).sortedBy { it.toString() }, b.map(::key).sortedBy { it.toString() })
    }

    @Test
    fun musicXmlRoundTripOfAllDemos() {
        for (demo in DemoSongs.all()) {
            val xml = MusicXmlExporter.export(demo)
            val back = MusicXmlImporter.import(xml.toByteArray(), "x")
            sameNotes(demo.notes, back.notes)
            assertEquals(demo.beatsPerMeasure, back.beatsPerMeasure, demo.title)
            assertEquals(demo.pickupBeats, back.pickupBeats, 1e-9, demo.title)
            assertEquals(demo.title, back.title)
        }
    }

    @Test
    fun voicesTiesTripletsFingeringAndTempo() {
        val song = Song(
            id = "t", title = "Voices & <Ties>", bpm = 90.0, beatsPerMeasure = 3, beatUnit = 4,
            notes = listOf(
                Note(72, 0.0, 3.0, Hand.RIGHT, finger = 5), // whole measure, overlaps the melody below
                Note(64, 0.0, 1.0, Hand.RIGHT, finger = 1), Note(65, 1.0, 1.0, Hand.RIGHT), Note(67, 2.0, 2.0, Hand.RIGHT), // tied over the bar
                Note(48, 0.0, 1.0 / 3, Hand.LEFT), Note(50, 1.0 / 3, 1.0 / 3, Hand.LEFT), Note(52, 2.0 / 3, 1.0 / 3, Hand.LEFT),
                Note(43, 3.0, 2.5, Hand.LEFT), Note(61, 4.0, 0.5, Hand.RIGHT), Note(70, 4.5, 0.25, Hand.RIGHT),
            ),
            tempoChanges = listOf(TempoChange(4.0, 60.0)), keyFifths = -2,
        )
        val xml = MusicXmlExporter.export(song)
        assertTrue("<fifths>-2</fifths>" in xml)
        assertTrue("<fingering>5</fingering>" in xml)
        assertTrue("<actual-notes>3</actual-notes>" in xml)
        assertTrue("Voices &amp; &lt;Ties&gt;" in xml)
        val back = MusicXmlImporter.import(xml.toByteArray(), "x")
        sameNotes(song.notes, back.notes)
        assertEquals(-2, back.keyFifths)
        assertEquals(90.0, back.bpm)
        assertEquals(listOf(4.0 to 60.0), back.tempoChanges.map { it.beat to it.bpm })
        assertEquals(5, back.notes.first { it.pitch == 72 }.finger)
        assertEquals(FingeringSource.FILE, back.fingeringSource)
    }

    @Test
    fun packageKeepsPartsSeparateAndRoundTrips() {
        val demo = DemoSongs.all().first()
        val song = demo.copy(
            notes = demo.notes.mapIndexed { i, n -> n.copy(finger = i % 5 + 1) },
            fingeringSource = FingeringSource.AUTO,
        )
        val pdf = "%PDF-1.4 fake".toByteArray()
        val pkg = SongPackage(song, mapOf("original.pdf" to pdf), sheetPdf = "%PDF sheet".toByteArray())
        val files = pkg.toFiles()
        for (f in listOf("manifest.json", "score.musicxml", "tiles.json", "hands.json", "sheet.pdf", "original.pdf")) assertNotNull(files[f], f)
        assertTrue("\"finger\":0" in files["tiles.json"]!!.decodeToString() && "\"finger\":3" !in files["tiles.json"]!!.decodeToString())
        val zip = pkg.toZip()
        assertTrue(SongPackage.isPackage(zip))
        val back = SongPackage.fromZip(zip)
        assertEquals(song, back.song)
        assertEquals(pdf.toList(), back.originals["original.pdf"]!!.toList())

        // without a tile file the notes come from the MusicXML score (format is exchangeable)
        val noTiles = SongPackage.fromFiles(files - "tiles.json")
        sameNotes(song.notes, noTiles.song.notes)
        assertEquals(song.id, noTiles.song.id)
        assertTrue(noTiles.song.notes.all { it.finger in 1..5 })
        assertTrue(abs(noTiles.song.bpm - song.bpm) < 1e-6)
    }
}
