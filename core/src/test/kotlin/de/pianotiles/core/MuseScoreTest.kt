package de.pianotiles.core

import de.pianotiles.core.io.MuseScoreImporter
import de.pianotiles.core.library.SongPackage
import de.pianotiles.core.model.Hand
import kotlin.test.Test
import kotlin.test.assertEquals

class MuseScoreTest {
    /** MuseScore 4 layout: Part with two staves, voices, tie spanners, tuplet with endTuplet, pick-up measure. */
    private val ms4 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <museScore version="4.20">
          <Score>
            <Division>480</Division>
            <metaTag name="composer">Anon</metaTag>
            <metaTag name="workTitle">Little Piece</metaTag>
            <Part id="1"><Staff id="1"/><Staff id="2"/><trackName>Piano</trackName></Part>
            <Staff id="1">
              <Measure len="1/4">
                <voice>
                  <KeySig><concertKey>-1</concertKey></KeySig>
                  <TimeSig><sigN>3</sigN><sigD>4</sigD></TimeSig>
                  <Tempo><tempo>1.5</tempo></Tempo>
                  <Chord><durationType>quarter</durationType><Note><pitch>67</pitch><Fingering><text>3</text></Fingering></Note></Chord>
                </voice>
              </Measure>
              <Measure>
                <voice>
                  <Chord><durationType>half</durationType><dots>1</dots>
                    <Note><Spanner type="Tie"><Tie/><next><location><measures>1</measures></location></next></Spanner><pitch>72</pitch></Note>
                    <Note><pitch>76</pitch></Note>
                  </Chord>
                </voice>
              </Measure>
              <Measure>
                <voice>
                  <Chord><durationType>quarter</durationType><Note><Spanner type="Tie"><prev><location><measures>-1</measures></location></prev></Spanner><pitch>72</pitch></Note></Chord>
                  <Tuplet><normalNotes>2</normalNotes><actualNotes>3</actualNotes></Tuplet>
                  <Chord><durationType>eighth</durationType><Note><pitch>74</pitch></Note></Chord>
                  <Chord><durationType>eighth</durationType><Note><pitch>76</pitch></Note></Chord>
                  <Chord><durationType>eighth</durationType><Note><pitch>77</pitch></Note></Chord>
                  <endTuplet/>
                  <Chord><acciaccatura/><durationType>eighth</durationType><Note><pitch>81</pitch></Note></Chord>
                  <Rest><durationType>quarter</durationType></Rest>
                </voice>
              </Measure>
            </Staff>
            <Staff id="2">
              <Measure len="1/4"><voice><Rest><durationType>quarter</durationType></Rest></voice></Measure>
              <Measure>
                <voice><Rest><durationType>measure</durationType><duration>3/4</duration></Rest></voice>
                <voice><location><fractions>1/4</fractions></location><Chord><durationType>half</durationType><Note><pitch>48</pitch></Note></Chord></voice>
              </Measure>
              <Measure><voice><Chord><durationType>half</durationType><dots>1</dots><Note><pitch>41</pitch></Note></Chord></voice></Measure>
            </Staff>
          </Score>
        </museScore>
    """.trimIndent()

    @Test
    fun museScore4() {
        val song = MuseScoreImporter.import(ms4.toByteArray(), "x")
        assertEquals("Little Piece", song.title)
        assertEquals("Anon", song.composer)
        assertEquals(3, song.beatsPerMeasure)
        assertEquals(90.0, song.bpm, 1e-9)
        assertEquals(1.0, song.pickupBeats)
        assertEquals(-1, song.keyFifths)
        val rh = song.notes.filter { it.hand == Hand.RIGHT }.sortedWith(compareBy({ it.start }, { it.pitch }))
        assertEquals(listOf(67, 72, 76, 74, 76, 77), rh.map { it.pitch })
        assertEquals(3, rh[0].finger)
        assertEquals(4.0, rh[1].duration, 1e-9) // tied 3 + 1
        assertEquals(1.0 / 3, rh[3].duration, 1e-9)
        assertEquals(5.0 + 1.0 / 3, rh[4].start, 1e-9)
        val lh = song.notes.filter { it.hand == Hand.LEFT }.sortedBy { it.start }
        assertEquals(listOf(48 to 2.0, 41 to 4.0), lh.map { it.pitch to it.start })
    }

    @Test
    fun museScore3RepeatsAndZip() {
        val ms3 = """
            <museScore version="3.02"><Score><Division>480</Division>
              <Part><Staff id="1"/></Part>
              <Staff id="1">
                <Measure><startRepeat/><voice><TimeSig><sigN>2</sigN><sigD>4</sigD></TimeSig>
                  <Tuplet id="1"><normalNotes>2</normalNotes><actualNotes>3</actualNotes></Tuplet>
                  <Chord><Tuplet>1</Tuplet><durationType>eighth</durationType><Note><pitch>60</pitch><Tie id="2"/></Note></Chord>
                  <Chord><Tuplet>1</Tuplet><durationType>eighth</durationType><Note><pitch>60</pitch><endSpanner id="2"/></Note></Chord>
                  <Chord><Tuplet>1</Tuplet><durationType>eighth</durationType><Note><pitch>62</pitch></Note></Chord>
                  <Chord><durationType>quarter</durationType><Note><pitch>64</pitch></Note></Chord>
                </voice><endRepeat>2</endRepeat></Measure>
                <Measure><voice><Chord><durationType>half</durationType><Note><pitch>65</pitch></Note></Chord></voice></Measure>
              </Staff></Score></museScore>
        """.trimIndent().toByteArray()
        val zipped = SongPackage.zip(mapOf("META-INF/container.xml" to "<container/>".toByteArray(), "Song.mscx" to ms3))
        val song = MuseScoreImporter.import(zipped, "Fallback")
        assertEquals("Fallback", song.title)
        val ns = song.notes.sortedBy { it.start }
        assertEquals(listOf(60, 62, 64, 60, 62, 64, 65), ns.map { it.pitch })
        assertEquals(2.0 / 3, ns[0].duration, 1e-9)
        assertEquals(2.0, ns[3].start, 1e-9)
    }
}
