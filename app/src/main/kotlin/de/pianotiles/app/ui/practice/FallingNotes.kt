package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.NoteNaming
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.practice.NoteState
import de.pianotiles.core.practice.PracticeEngine
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

fun handColor(hand: Hand) = if (hand == Hand.RIGHT) PtColors.RightHand else PtColors.LeftHand
fun handColorDark(hand: Hand) = if (hand == Hand.RIGHT) PtColors.RightHandDark else PtColors.LeftHandDark

/**
 * The "Piano Tiles" view: notes fall from the top towards the hit line at the bottom, which sits
 * directly on top of the keyboard. Vertical drags scrub through the piece, a double tap loops the
 * measure at the hit line.
 */
@Composable
fun FallingNotes(
    engine: PracticeEngine,
    range: IntRange,
    frame: Long,
    lookaheadSeconds: Float,
    naming: NoteNaming,
    showNames: Boolean,
    showMeasureNumbers: Boolean,
    onScrub: (beats: Double) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val song = engine.song
    val noteOrder = remember(song) { song.notes.indices.sortedBy { song.notes[it].start } }
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(lookaheadSeconds) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val pxPerBeat = size.height / (lookaheadSeconds * engine.song.bpm * engine.speed / 60.0)
                    onScrub(dragAmount / pxPerBeat)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) },
    ) {
        frame.also { } // read to redraw every frame
        val layout = KeyLayout(range.first, range.last, size.width)
        val h = size.height
        val beatsVisible = lookaheadSeconds * song.bpm * engine.speed / 60.0
        val pxPerBeat = (h / beatsVisible).toFloat()
        val pos = engine.position
        fun y(beat: Double) = (h - (beat - pos) * pxPerBeat).toFloat()

        // background: subtle lanes for black keys
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0F1C), Color(0xFF141A30))))
        for (p in range) if (layout.isBlack(p)) {
            drawRect(Color(0x14FFFFFF).copy(alpha = 0.035f), Offset(layout.left(p), 0f), Size(layout.blackWidth, h))
        }
        for (p in layout.whites) if (p % 12 == 0) {
            drawLine(Color(0x22FFFFFF), Offset(layout.left(p), 0f), Offset(layout.left(p), h), 1f)
        }

        // loop region
        val loop = engine.loop
        if (loop.enabled && loop.endBeat > loop.startBeat) {
            val top = y(loop.endBeat); val bottom = y(loop.startBeat)
            if (bottom > 0 && top < h) {
                drawRect(PtColors.Loop.copy(alpha = 0.07f), Offset(0f, max(0f, top)), Size(size.width, min(h, bottom) - max(0f, top)))
            }
            for (b in listOf(loop.startBeat, loop.endBeat)) {
                val yy = y(b)
                if (yy in 0f..h) drawLine(PtColors.Loop, Offset(0f, yy), Offset(size.width, yy), 3f)
            }
        }

        // measure lines
        val firstM = song.measureIndexAt(max(0.0, pos))
        var m = max(0, firstM - 1)
        while (true) {
            val b = song.measureStart(m)
            val yy = y(b)
            if (yy < -20) break
            if (yy <= h + 1) {
                drawLine(Color(0x33FFFFFF), Offset(0f, yy), Offset(size.width, yy), 1.5f)
                if (showMeasureNumbers) drawLabel(measurer, "${m + 1}", Offset(6f, yy - 22f), Color(0x88FFFFFF), 12)
            }
            m++
            if (m > song.measureCount + 1) break
        }

        // expected keys: light beams
        val expected = engine.expectedPitches
        for (p in expected) if (layout.contains(p)) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f))),
                Offset(layout.left(p), 0f), Size(layout.keyWidth(p), h),
            )
        }

        // notes
        val minBeat = pos - 0.5
        val maxBeat = pos + beatsVisible + 0.5
        val radius = CornerRadius(min(layout.whiteWidth * 0.22f, 10f))
        for (pass in 0..1) { // white-key notes first, then black-key notes on top
            for (i in noteOrder) {
                val n = song.notes[i]
                if (n.start > maxBeat) break
                if (n.end < minBeat || !layout.contains(n.pitch)) continue
                val black = layout.isBlack(n.pitch)
                if ((pass == 0) == black) continue
                val state = engine.noteStates[i]
                val top = y(n.end); val bottom = y(n.start)
                val inset = if (black) 1f else layout.whiteWidth * 0.08f
                val x = layout.left(n.pitch) + inset
                val w = layout.keyWidth(n.pitch) - 2 * inset
                val tileH = max(6f, bottom - top - 2f)
                val base = handColor(n.hand)
                val (fill, alpha) = when (state) {
                    NoteState.PENDING -> base to 1f
                    NoteState.HIT -> PtColors.Hit to 0.85f
                    NoteState.MISSED -> PtColors.Miss to 0.75f
                    NoteState.AUTO -> base to 0.38f
                    NoteState.INACTIVE -> Color(0xFF5A6080) to 0.35f
                }
                val brush = Brush.verticalGradient(
                    listOf(fill.copy(alpha = alpha), handColorDark(n.hand).copy(alpha = alpha * 0.9f)),
                    startY = top, endY = top + tileH,
                )
                drawRoundRect(brush, Offset(x, top), Size(w, tileH), radius)
                if (state == NoteState.PENDING) drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(x, top), Size(w, tileH), radius, style = Stroke(1.2f))
                if (showNames && naming != NoteNaming.NONE && tileH > 20 && w > 14) {
                    val label = Pitch.name(n.pitch, naming)
                    val fs = if (w > 34) 13 else 10
                    drawLabel(measurer, label, Offset(x + 3f, bottom - fs * 2.2f), Color(0xEE0B0F1C), fs, bold = true, maxWidth = w - 4f)
                }
            }
        }

        // hit line
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.18f)), startY = h - 26f, endY = h), Offset(0f, h - 26f), Size(size.width, 26f))
        drawLine(if (engine.isWaiting) PtColors.Hit else Color.White.copy(alpha = 0.7f), Offset(0f, h - 1.5f), Offset(size.width, h - 1.5f), 3f)

        // count-in
        if (engine.countInRemaining > 0) {
            val beatsPerClick = if (song.beatUnit == 8 && song.beatsPerMeasure % 3 == 0) 1.5 else 4.0 / song.beatUnit
            val remainingClicks = ceil(engine.countInRemaining / beatsPerClick - 1e-6).toInt()
            drawLabel(measurer, "$remainingClicks", Offset(size.width / 2 - 20f, h / 2 - 50f), Color.White.copy(alpha = 0.8f), 64, bold = true)
        }
    }
}

internal fun DrawScope.drawLabel(
    measurer: TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Int,
    bold: Boolean = false, maxWidth: Float = Float.MAX_VALUE,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    if (layout.size.width > maxWidth) return
    drawText(layout, topLeft = at)
}
