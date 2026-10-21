package de.pianotiles.core

import de.pianotiles.core.io.MidiImporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.model.Hand
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportTest {

    private fun vlq(out: ByteArrayOutputStream, value: Int) {
        var v = value
        val bytes = ArrayList<Int>()
        bytes.add(v and 0x7F)
        v = v shr 7
        while (v > 0) { bytes.add((v and 0x7F) or 0x80); v = v shr 7 }
        bytes.reversed().forEach { out.write(it) }
    }

    private fun track(events: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("MTrk".toByteArray())
        val l = events.size
        o.write(byteArrayOf((l shr 24).toByte(), (l shr 16).toByte(), (l shr 8).toByte(), l.toByte()))
        o.write(events)
        return o.toByteArray()
    }

    /** Two tracks: right hand C5 D5 (quarters), left hand C3 (half), tempo 90, 3/4. */
    private fun sampleMidi(): ByteArray {
        val tpq = 480
        val t0 = ByteArrayOutputStream().apply {
            vlq(this, 0); write(byteArrayOf(0xFF.toByte(), 0x51, 3)); val us = 60_000_000 / 90
            write(byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))
            vlq(this, 0); write(byteArrayOf(0xFF.toByte(), 0x58, 4, 3, 2, 24, 8))
            vlq(this, 0); write(byteArrayOf(0xFF.toByte(), 0x03, 4)); write("Test".toByteArray())
            // right hand with running status
            vlq(this, 0); write(byteArrayOf(0x90.toByte(), 72, 100))
            vlq(this, tpq); write(byteArrayOf(72, 0))
            vlq(this, 0); write(byteArrayOf(74, 90))
            vlq(this, tpq); write(byteArrayOf(0x80.toByte(), 74, 0))
            vlq(this, 0); write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        }.toByteArray()
        val t1 = ByteArrayOutputStream().apply {
            vlq(this, 0); write(byteArrayOf(0x91.toByte(), 48, 80))
            vlq(this, tpq * 2); write(byteArrayOf(0x81.toByte(), 48, 0))
            vlq(this, 0); write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        }.toByteArray()
        val o = ByteArrayOutputStream()
        o.write("MThd".toByteArray()); o.write(byteArrayOf(0, 0, 0, 6, 0, 1, 0, 2, (tpq shr 8).toByte(), tpq.toByte()))
        o.write(track(t0)); o.write(track(t1))
        return o.toByteArray()
    }

    @Test
    fun midiImport() {
        val song = MidiImporter.import(sampleMidi(), "fallback")
        assertEquals("Test", song.title)
        assertEquals(90.0, song.bpm, 0.5)
        assertEquals(3, song.beatsPerMeasure)
        assertEquals(3, song.notes.size)
        val c3 = song.notes.first { it.pitch == 48 }
        assertEquals(Hand.LEFT, c3.hand)
        assertEquals(2.0, c3.duration, 1e-6)
        val d5 = song.notes.first { it.pitch == 74 }
        assertEquals(1.0, d5.start, 1e-6)
        assertEquals(Hand.RIGHT, d5.hand)
    }

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
<score-partwise version="4.0">
  <work><work-title>Kleines Stück</work-title></work>
  <identification><creator type="composer">Anon</creator></identification>
  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
  <part id="P1">
    <measure number="0" implicit="yes">
      <attributes><divisions>2</divisions><key><fifths>1</fifths></key><time><beats>3</beats><beat-type>4</beat-type></time><staves>2</staves></attributes>
      <direction><sound tempo="72"/></direction>
      <note><pitch><step>D</step><octave>5</octave></pitch><duration>2</duration><staff>1</staff></note>
    </measure>
    <measure number="1">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><tie type="start"/><staff>1</staff></note>
      <note><chord/><pitch><step>B</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff></note>
      <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>2</duration><staff>1</staff></note>
      <backup><duration>6</duration></backup>
      <note><pitch><step>G</step><octave>2</octave></pitch><duration>6</duration><staff>2</staff></note>
    </measure>
    <measure number="2">
      <note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration><tie type="stop"/><staff>1</staff></note>
      <note><rest/><duration>4</duration><staff>1</staff></note>
    </measure>
  </part>
</score-partwise>"""

    @Test
    fun musicXmlImport() {
        val song = MusicXmlImporter.import(xml.toByteArray(), "fallback")
        assertEquals("Kleines Stück", song.title)
        assertEquals("Anon", song.composer)
        assertEquals(72.0, song.bpm, 1e-6)
        assertEquals(1.0, song.pickupBeats, 1e-6)
        val g4 = song.notes.first { it.pitch == 67 }
        assertEquals(1.0, g4.start, 1e-6)
        assertEquals(4.0, g4.duration, 1e-6) // tied through to the end of the tie-stop note
        val fis = song.notes.first { it.pitch == 66 }
        assertEquals(3.0, fis.start, 1e-6)
        val bass = song.notes.first { it.pitch == 43 }
        assertEquals(Hand.LEFT, bass.hand)
        assertEquals(1.0, bass.start, 1e-6)
        assertEquals(5, song.notes.size)
        assertEquals(1, song.measureIndexAt(1.0))
        assertEquals(2, song.measureIndexAt(4.0))
    }

    @Test
    fun mxlImport() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<container><rootfiles><rootfile full-path="score.xml"/></rootfiles></container>""".toByteArray())
            z.putNextEntry(ZipEntry("score.xml")); z.write(xml.toByteArray())
        }
        val song = MusicXmlImporter.import(bos.toByteArray(), "x")
        assertTrue(song.notes.isNotEmpty())
    }
}
