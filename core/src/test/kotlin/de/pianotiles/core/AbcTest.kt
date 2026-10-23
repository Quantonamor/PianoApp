package de.pianotiles.core

import de.pianotiles.core.io.AbcImporter
import de.pianotiles.core.model.Hand
import kotlin.test.Test
import kotlin.test.assertEquals

class AbcTest {
    private fun abc(s: String) = AbcImporter.import(s.trimIndent().toByteArray(), "fallback")

    @Test
    fun headerKeyLengthsAndAccidentals() {
        val song = abc("""
            X:1
            T:Test Tune
            C:Trad.
            M:3/4
            L:1/8
            Q:1/4=90
            K:G
            % comment
            GA Bc d2 | f2 =f2 ^f2 | g6 |]
        """)
        assertEquals("Test Tune", song.title)
        assertEquals("Trad.", song.composer)
        assertEquals(3, song.beatsPerMeasure)
        assertEquals(90.0, song.bpm)
        assertEquals(1, song.keyFifths)
        val p = song.notes.map { it.pitch }
        // G4 A4 B4 C5 D5 | F#5 (key) F5 (natural) F#5 | G5
        assertEquals(listOf(67, 69, 71, 72, 74, 78, 77, 78, 79), p)
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0), song.notes.map { it.start })
        assertEquals(3.0, song.notes.last().duration)
    }

    @Test
    fun barAccidentalsLastUntilTheBarLine() {
        val song = abc("""
            X:1
            M:4/4
            L:1/4
            K:C
            ^F F F2 | F4 |
        """)
        assertEquals(listOf(66, 66, 66, 65), song.notes.map { it.pitch })
    }

    @Test
    fun chordsTiesBrokenRhythmTupletsOctaves() {
        val song = abc("""
            X:1
            M:4/4
            L:1/8
            K:C
            [CEG]2 c'>B, (3cde C,,2- | C,,8 |
        """)
        val ch = song.notes.filter { it.start == 0.0 }.map { it.pitch }.sorted()
        assertEquals(listOf(60, 64, 67), ch)
        val c6 = song.notes.first { it.pitch == 84 }
        assertEquals(1.0, c6.start); assertEquals(0.75, c6.duration, 1e-9)
        val b2 = song.notes.first { it.pitch == 59 }
        assertEquals(1.75, b2.start, 1e-9); assertEquals(0.25, b2.duration, 1e-9)
        val trip = song.notes.filter { it.pitch in listOf(72, 74, 76) }
        assertEquals(3, trip.size)
        assertEquals(1.0 / 3, trip[0].duration, 1e-9)
        val low = song.notes.filter { it.pitch == 36 }
        assertEquals(1, low.size) // tied over the bar
        assertEquals(5.0, low[0].duration, 1e-9)
    }

    @Test
    fun repeatsWithEndingsAndPickup() {
        val song = abc("""
            X:1
            M:2/4
            L:1/4
            K:D
            A |: d e |1 f2 :|2 g2 |]
        """)
        assertEquals(1.0, song.pickupBeats)
        // A | d e f | d e g
        assertEquals(listOf(69, 74, 76, 78, 74, 76, 79), song.notes.sortedBy { it.start }.map { it.pitch })
    }

    @Test
    fun twoVoicesBecomeTwoHands() {
        val song = abc("""
            X:1
            T:Duet
            M:4/4
            L:1/4
            V:1 clef=treble
            V:2 clef=bass
            K:F
            V:1
            c d e f |
            V:2
            F,,4 |
        """)
        assertEquals(1, song.notes.count { it.hand == Hand.LEFT })
        assertEquals(4, song.notes.count { it.hand == Hand.RIGHT })
        assertEquals(41, song.notes.first { it.hand == Hand.LEFT }.pitch)
        assertEquals(listOf(72, 74, 76, 77), song.notes.filter { it.hand == Hand.RIGHT }.sortedBy { it.start }.map { it.pitch })
        assertEquals(-1, song.keyFifths)
    }

    @Test
    fun keys() {
        assertEquals(-3, AbcImporter.parseKey("Cm"))
        assertEquals(-2, AbcImporter.parseKey("Bb"))
        assertEquals(6, AbcImporter.parseKey("F#"))
        assertEquals(1, AbcImporter.parseKey("Ador"))
        assertEquals(1, AbcImporter.parseKey("Dmix"))
        assertEquals(0, AbcImporter.parseKey("none"))
    }
}
