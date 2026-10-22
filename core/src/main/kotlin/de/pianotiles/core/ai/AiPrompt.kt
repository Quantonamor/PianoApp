package de.pianotiles.core.ai

import de.pianotiles.core.edit.MeasureOps
import de.pianotiles.core.edit.RelNote
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/** One corrected measure/hand as proposed by the AI (measure is 0-based). */
data class AiCorrection(val measure: Int, val hand: Hand, val notes: List<RelNote>, val reason: String)

/** Wire format of the model's answer (validated against [AiPrompt.outputSchema] by the API). */
@Serializable
internal data class WireAnswer(val summary: String, val corrections: List<WireCorrection>)

@Serializable
internal data class WireCorrection(val measure: Int, val hand: String, val notes: List<WireNote>, val reason: String)

@Serializable
internal data class WireNote(val pitch: String, val start: Double, val duration: Double)

/**
 * The fixed prompt for "KI-nachbessern": the model gets the image of one PDF page plus the notes our
 * recognition produced for the measures on that page, and returns only the measures that need changes.
 */
object AiPrompt {

    val SYSTEM = """
You are an expert music engraver and proofreader for piano sheet music. A piano practice app read a
printed score with optical music recognition (OMR). You receive the image of one page of that score and
the notes the OMR produced for every measure on the page. Your job is to find where the transcription
differs from what is printed and to return corrected notes for exactly those measures.

How the transcription is written
- Measures are numbered in the order the app plays them ("M1" is the first measure of the piece; a
  pick-up/anacrusis counts as M1). For each measure you get its approximate box on the page as
  percentages of the page width/height, so you can find it in the image.
- "R" is the right hand (normally the upper staff), "L" the left hand (normally the lower staff).
- Each note is PITCH@START/DURATION. PITCH uses scientific pitch notation with sharps (#) or flats (b),
  e.g. C4 = middle C, F#5, Bb2. START is the offset from the beginning of the measure and DURATION the
  written length, both in quarter-note beats (eighth = 0.5, dotted quarter = 1.5, triplet eighth = 1/3,
  written as decimals like 0.333). Notes with the same START form a chord.

What to check, measure by measure
- Every printed note head of the piano staves is present with the right pitch: clef (including clef
  changes and octave-transposing clefs), key signature, accidentals (they last until the bar line),
  ledger lines, and which staff/hand it belongs to.
- Rhythm: note values, dots, beams, flags, tuplets and rests. Rests are not listed - they only appear as
  gaps. Within one voice the notes of a hand normally fill the measure (together with rests) exactly.
- Notes that are tied across a bar line are written once, in the measure where they start, with the
  combined duration. Grace notes, ornaments, fingering, dynamics, lyrics and pedal marks are ignored.
- Repeats are not expanded: transcribe each printed measure once, in printed order.
- Ignore staves that are not part of the piano (e.g. a vocal line above the piano).

How to answer
- Only include a measure/hand when something must change. Copy its complete corrected note list for
  that hand (not only the changed notes) - the app replaces that hand of that measure with your list.
- If the transcription is already right, return an empty corrections list.
- When the image is unclear, keep the transcription as it is; do not guess wildly.
- "reason": a very short explanation in German for a piano student (e.g. "Fis statt F (Vorzeichen)",
  "Achtel statt Viertel", "fehlender Akkordton E4").
- "summary": one or two German sentences about what you checked and changed on this page.
""".trimIndent()

    /** JSON schema for structured output (output_config.format). */
    val outputSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "summary" to mapOf("type" to "string"),
            "corrections" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "measure" to mapOf("type" to "integer", "description" to "measure number as given (M5 -> 5)"),
                        "hand" to mapOf("type" to "string", "enum" to listOf("R", "L")),
                        "notes" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "pitch" to mapOf("type" to "string"),
                                    "start" to mapOf("type" to "number"),
                                    "duration" to mapOf("type" to "number"),
                                ),
                                "required" to listOf("pitch", "start", "duration"),
                                "additionalProperties" to false,
                            ),
                        ),
                        "reason" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("measure", "hand", "notes", "reason"),
                    "additionalProperties" to false,
                ),
            ),
        ),
        "required" to listOf("summary", "corrections"),
        "additionalProperties" to false,
    )

    /** Measures (0-based) shown on PDF page [page]. */
    fun measuresOnPage(song: Song, page: Int): List<Int> = song.sheetMeasures.filter { it.page == page }.map { it.measure }.sorted()

    /** The user message text for one page. */
    fun pageText(song: Song, page: Int, pageCount: Int, keyName: String?, warnings: List<String>, retryErrors: List<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("Piece: \"${song.title}\". Page ${page + 1} of $pageCount (the image).\n")
        sb.append("Time signature: ${song.beatsPerMeasure}/${song.beatUnit} = ${fmt(song.measureLength)} quarter beats per measure")
        if (song.pickupBeats > 0) sb.append("; M1 is a pick-up of ${fmt(song.pickupBeats)} beats")
        sb.append(".\n")
        if (keyName != null) sb.append("Key signature detected by the OMR: $keyName (may be wrong).\n")
        val flagged = song.flaggedMeasures.filter { m -> song.sheetMeasures.any { it.page == page && it.measure == m } }
        if (flagged.isNotEmpty()) sb.append("The OMR was unsure about the rhythm of: ${flagged.joinToString { "M${it + 1}" }}.\n")
        if (warnings.isNotEmpty()) sb.append("OMR warnings: ${warnings.joinToString("; ")}\n")
        sb.append("\nTranscription:\n")
        for (sm in song.sheetMeasures.filter { it.page == page }.sortedBy { it.measure }) {
            val m = sm.measure
            sb.append("M${m + 1} (box x ${pct(sm.x0)}-${pct(sm.x1)}%, y ${pct(sm.y0)}-${pct(sm.y1)}%, length ${fmt(MeasureOps.lengthOf(song, m))}):\n")
            val start = song.measureStart(m)
            for (hand in listOf(Hand.RIGHT, Hand.LEFT)) {
                val ns = MeasureOps.notesIn(song, m, hand).sortedWith(compareBy({ it.start }, { it.pitch }))
                sb.append("  ${if (hand == Hand.RIGHT) "R" else "L"}: ")
                sb.append(if (ns.isEmpty()) "(nothing)" else ns.joinToString(" ") { "${spn(it.pitch)}@${fmt(it.start - start)}/${fmt(it.duration)}" })
                sb.append('\n')
            }
        }
        if (retryErrors.isNotEmpty()) {
            sb.append("\nYour previous answer for this page could not be used: ${retryErrors.joinToString("; ")}. ")
            sb.append("Please answer again and follow the format exactly.\n")
        }
        return sb.toString()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses and validates the model's JSON. Returns the usable corrections, the summary and a list of
     * problems (empty if everything was fine).
     */
    fun parse(text: String, song: Song, allowed: Collection<Int>): Triple<List<AiCorrection>, String, List<String>> {
        val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val answer = try {
            json.decodeFromString(WireAnswer.serializer(), body)
        } catch (e: Exception) {
            return Triple(emptyList(), "", listOf("invalid JSON (${e.message?.take(120)})"))
        }
        val problems = ArrayList<String>()
        val out = ArrayList<AiCorrection>()
        for (c in answer.corrections) {
            val m = c.measure - 1
            if (m !in allowed) { problems.add("M${c.measure} is not on this page"); continue }
            val hand = when (c.hand.uppercase()) { "R" -> Hand.RIGHT; "L" -> Hand.LEFT; else -> null }
            if (hand == null) { problems.add("M${c.measure}: hand must be R or L"); continue }
            val len = MeasureOps.lengthOf(song, m)
            val notes = ArrayList<RelNote>()
            var ok = true
            for (n in c.notes) {
                val p = parseSpn(n.pitch)
                if (p == null || p !in Pitch.LOWEST_PIANO..Pitch.HIGHEST_PIANO) { problems.add("M${c.measure}: unknown pitch '${n.pitch}'"); ok = false; break }
                if (n.start < -1e-6 || n.start >= len - 1e-3 || n.duration <= 0 || n.duration > 16) {
                    problems.add("M${c.measure}: note ${n.pitch} start ${fmt(n.start)}/duration ${fmt(n.duration)} outside the measure (length ${fmt(len)})"); ok = false; break
                }
                notes.add(RelNote(p, snap(n.start), snap(n.duration), hand))
            }
            if (ok) out.add(AiCorrection(m, hand, notes, c.reason))
        }
        return Triple(out, answer.summary, problems)
    }

    /** Scientific pitch name with sharps: 60 -> C4, 61 -> C#4. */
    fun spn(pitch: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[Math.floorMod(pitch, 12)] + (Math.floorDiv(pitch, 12) - 1)
    }

    /** Parses "C4", "F#5", "Bb2", "Cb4", "E#3", "C##4", "Bbb3" (also German "H" = B). */
    fun parseSpn(s: String): Int? {
        val t = s.trim().replace("♯", "#").replace("♭", "b")
        val m = Regex("^([A-Ha-h])(#{0,2}|b{0,2})(-?\\d)$").find(t) ?: return null
        val letter = when (m.groupValues[1].uppercase()) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B", "H" -> 11; else -> return null
        }
        val acc = m.groupValues[2].let { if (it.startsWith("#")) it.length else -it.length }
        val octave = m.groupValues[3].toInt()
        return (octave + 1) * 12 + letter + acc
    }

    /** Snaps to 1/48 beat so triplets written as 0.333 become exact thirds. */
    private fun snap(v: Double): Double = (v * 48).roundToInt() / 48.0

    private fun pct(v: Float) = (v * 100).roundToInt()

    fun fmt(v: Double): String {
        val r = (v * 1000).roundToInt() / 1000.0
        return if (abs(r - r.roundToInt()) < 1e-9) r.roundToInt().toString() else r.toString()
    }
}
