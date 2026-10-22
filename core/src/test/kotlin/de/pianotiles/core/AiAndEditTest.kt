package de.pianotiles.core

import de.pianotiles.core.ai.AiEvent
import de.pianotiles.core.ai.AiFixRunner
import de.pianotiles.core.ai.AiPrompt
import de.pianotiles.core.ai.PageImage
import de.pianotiles.core.edit.MeasureOps
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.SheetMeasure
import de.pianotiles.core.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiAndEditTest {
    private fun song(): Song {
        val notes = ArrayList<Note>()
        for (m in 0 until 4) {
            for (b in 0 until 4) notes.add(Note(60 + m, m * 4.0 + b, 1.0, Hand.RIGHT))
            notes.add(Note(48, m * 4.0, 4.0, Hand.LEFT))
        }
        val sheet = (0 until 4).map { SheetMeasure(it, it / 2, 0.1f + (it % 2) * 0.4f, 0.1f, 0.5f + (it % 2) * 0.4f, 0.3f) }
        return Song(id = "t", title = "Test", notes = notes, sheetMeasures = sheet, flaggedMeasures = listOf(2))
    }

    @Test
    fun measureOps() {
        val s = song()
        assertEquals(5, MeasureOps.notesIn(s, 1).size)
        val ins = MeasureOps.insertBefore(s, 1)
        assertEquals(0, MeasureOps.notesIn(ins, 1).size)
        assertEquals(61, MeasureOps.notesIn(ins, 2, Hand.RIGHT).first().pitch)
        assertEquals(listOf(3), ins.flaggedMeasures)
        val del = MeasureOps.delete(s, 1)
        assertEquals(62, MeasureOps.notesIn(del, 1, Hand.RIGHT).first().pitch)
        assertEquals(3, del.sheetMeasures.size)
        val pasted = MeasureOps.replace(s, 3, MeasureOps.copy(s, 0))
        assertEquals(60, MeasureOps.notesIn(pasted, 3, Hand.RIGHT).first().pitch)
        val swapped = MeasureOps.swapHands(s, 0)
        assertEquals(4, MeasureOps.notesIn(swapped, 0, Hand.LEFT).size)
        // measure 2 is flagged, measure 1 gets a hole in the right hand
        val holey = s.copy(notes = s.notes.filterNot { it.hand == Hand.RIGHT && it.start in 4.5..7.5 })
        val issues = MeasureOps.issues(holey).associateBy { it.measure }
        assertTrue(1 in issues && 2 in issues && 0 !in issues, issues.toString())
    }

    @Test
    fun pitchNames() {
        assertEquals(60, AiPrompt.parseSpn("C4"))
        assertEquals(66, AiPrompt.parseSpn("F#4"))
        assertEquals(46, AiPrompt.parseSpn("Bb2"))
        assertEquals(59, AiPrompt.parseSpn("Cb4"))
        assertEquals(21, AiPrompt.parseSpn("A0"))
        assertEquals("C#4", AiPrompt.spn(61))
    }

    @Test
    fun promptLanguage() {
        assertTrue("explanation in German" in AiPrompt.SYSTEM && "Fis statt F" in AiPrompt.SYSTEM)
        val en = AiPrompt.system("English")
        assertTrue("explanation in English" in en && "two English sentences" in en && "Fis statt F" !in en, en)
    }

    @Test
    fun promptAndParse() {
        val s = song()
        val text = AiPrompt.pageText(s, 1, 2, "C-Dur", emptyList())
        assertTrue("M3 (box" in text && "M4" in text && "M1 " !in text, text)
        assertTrue("unsure about the rhythm of: M3" in text, text)
        val answer = """{"summary":"ok","corrections":[
            {"measure":3,"hand":"R","notes":[{"pitch":"E4","start":0,"duration":0.333},{"pitch":"F#4","start":0.333,"duration":0.667}],"reason":"Triole"},
            {"measure":1,"hand":"R","notes":[],"reason":"falsche Seite"}]}"""
        val (corr, summary, problems) = AiPrompt.parse(answer, s, AiPrompt.measuresOnPage(s, 1))
        assertEquals("ok", summary)
        assertEquals(1, corr.size)
        assertEquals(1, problems.size)
        assertEquals(1.0 / 3, corr[0].notes[0].duration, 1e-9)
        val fixed = MeasureOps.replace(s, corr[0].measure, corr[0].notes, corr[0].hand)
        assertEquals(listOf(64, 66), MeasureOps.notesIn(fixed, 2, Hand.RIGHT).map { it.pitch })
        assertEquals(1, MeasureOps.notesIn(fixed, 2, Hand.LEFT).size)
    }

    @Test
    fun runnerRetriesInvalidAnswer() {
        val s = song()
        var calls = 0
        val events = ArrayList<AiEvent>()
        val runner = AiFixRunner { _, text ->
            calls++
            if (calls == 1) "not json"
            else {
                assertTrue("could not be used" in text)
                """{"summary":"x","corrections":[{"measure":1,"hand":"L","notes":[{"pitch":"C3","start":0,"duration":2}],"reason":"halbe"}]}"""
            }
        }
        val result = runner.run(s, listOf(0), { PageImage(it, ByteArray(1), false) }, null, emptyList()) { events.add(it) }
        assertEquals(2, calls)
        assertEquals(1, result.size)
        assertTrue(events.any { it is AiEvent.PageResult })
    }
}
