package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
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

/** Short-lived sparks where a note reaches the hit line. Mutated while drawing (redrawn every frame anyway). */
private class Sparks {
    class P(var x: Float, var y: Float, val vx: Float, val vy: Float, val color: Color, val born: Long)
    val list = ArrayList<P>()
    var lastPos = Double.NaN
    val rnd = java.util.Random(7)
}

/**
 * The falling notes: rounded, glossy bars in the hand colour fall onto a glowing hit line that sits directly on
 * the keyboard; note names sit at the bottom of each bar, sparks fly where a note is struck. Faint lines mark every
 * C (vertical) and every measure (horizontal). Vertical drags scrub, a tap starts/pauses, a double tap loops the
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
    showFingers: Boolean,
    onScrub: (beats: Double) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 512) // note names repeat: measure each once
    val song = engine.song
    val noteOrder = remember(song) { song.notes.indices.sortedBy { song.notes[it].start } }
    val sparks = remember { Sparks() }
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
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

        // background: almost black, slightly lighter towards the keys
        drawRect(Brush.verticalGradient(listOf(Color(0xFF040508), Color(0xFF0A0C14))))
        for (p in layout.whites) {
            val x = layout.left(p)
            when (p % 12) {
                0 -> drawLine(Color.White.copy(alpha = 0.10f), Offset(x, 0f), Offset(x, h), 1.2f)
                5 -> drawLine(Color.White.copy(alpha = 0.045f), Offset(x, 0f), Offset(x, h), 1f)
            }
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
                if (yy in 0f..h) drawLine(PtColors.Loop, Offset(0f, yy), Offset(size.width, yy), 2.5f)
            }
        }

        // measure lines
        val firstM = song.measureIndexAt(max(0.0, pos))
        var m = max(0, firstM - 1)
        while (true) {
            val yy = y(song.measureStart(m))
            if (yy < -20) break
            if (yy <= h + 1) {
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, yy), Offset(size.width, yy), 1f)
                if (showMeasureNumbers) drawLabel(measurer, "${m + 1}", Offset(4f, yy - 16f), Color.White.copy(alpha = 0.35f), 10)
            }
            m++
            if (m > song.measureCount + 1) break
        }

        // notes
        val minBeat = pos - 0.5
        val maxBeat = pos + beatsVisible + 0.5
        for (pass in 0..1) { // white-key notes first, then black-key notes on top
            for (i in noteOrder) {
                val n = song.notes[i]
                if (n.start > maxBeat) break
                if (n.end < minBeat || !layout.contains(n.pitch)) continue
                val black = layout.isBlack(n.pitch)
                if ((pass == 0) == black) continue
                val state = engine.noteStates[i]
                val top = y(n.end); val bottom = y(n.start)
                val w = if (black) layout.blackWidth * 0.92f else layout.whiteWidth * 0.80f
                val x = layout.left(n.pitch) + (layout.keyWidth(n.pitch) - w) / 2
                val tileH = max(w * 0.9f, bottom - top - 2f)
                val tileTop = bottom - tileH
                val base = handColor(n.hand)
                val dark = handColorDark(n.hand)
                val (c1, c2, alpha) = when (state) {
                    NoteState.PENDING -> Triple(base, dark, 1f)
                    NoteState.HIT -> Triple(lerp(base, Color.White, 0.35f), base, 1f)
                    NoteState.MISSED -> Triple(PtColors.Miss, Color(0xFF8E1A1A), 0.8f)
                    NoteState.AUTO -> Triple(base, dark, 0.55f)
                    NoteState.INACTIVE -> Triple(Color(0xFF5A6080), Color(0xFF353A52), 0.45f)
                }
                val r = CornerRadius(min(w / 2f, 18f))
                val sounding = n.start <= pos && n.end > pos
                // glow
                if (sounding || state == NoteState.HIT) {
                    drawRoundRect(base.copy(alpha = 0.28f * alpha), Offset(x - 4f, tileTop - 4f), Size(w + 8f, tileH + 8f), CornerRadius(r.x + 4f))
                }
                // glossy body: darker edges, light core
                drawRoundRect(
                    Brush.horizontalGradient(
                        0f to c2.copy(alpha = alpha), 0.35f to c1.copy(alpha = alpha), 0.6f to lerp(c1, Color.White, 0.25f).copy(alpha = alpha), 1f to c2.copy(alpha = alpha),
                        startX = x, endX = x + w,
                    ),
                    Offset(x, tileTop), Size(w, tileH), r,
                )
                if (showFingers && n.finger in 1..5 && tileH > w * 2.2f) {
                    drawLabel(measurer, "${n.finger}", Offset(x + w / 2 - 4f, tileTop + 4f), Color.White.copy(alpha = 0.9f * alpha), if (w > 26) 11 else 9, bold = true)
                }
                if (showNames && naming != NoteNaming.NONE && w > 11) {
                    val label = Pitch.name(n.pitch, naming)
                    val fs = when { w > 34 -> 13; w > 22 -> 11; else -> 9 }
                    noteName(measurer, label, x + w / 2, bottom - 3f, fs, alpha, w)
                }
            }
        }

        // hit line: soft glow and a bright line in the colour of what is sounding
        val active = engine.soundingNotes().map { song.notes[it] }
        val lineColor = when {
            engine.isWaiting -> PtColors.Hit
            active.isNotEmpty() -> handColor(active.last().hand)
            else -> Color.White
        }
        val glow = if (active.isNotEmpty() || engine.isWaiting) 0.45f else 0.18f
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, lineColor.copy(alpha = glow)), startY = h - 34f, endY = h), Offset(0f, h - 34f), Size(size.width, 34f))
        drawLine(lerp(lineColor, Color.White, 0.5f), Offset(0f, h - 1.5f), Offset(size.width, h - 1.5f), 3f)
        for (n in active) if (layout.contains(n.pitch)) {
            val cx = layout.center(n.pitch)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, handColor(n.hand).copy(alpha = 0.7f)), startY = h - 60f, endY = h),
                Offset(cx - layout.keyWidth(n.pitch) / 2, h - 60f), Size(layout.keyWidth(n.pitch), 60f))
        }

        // sparks for notes that just reached the line
        val now = System.nanoTime()
        if (!sparks.lastPos.isNaN() && pos > sparks.lastPos && pos - sparks.lastPos < 0.5) {
            for (n in song.notes) if (n.start > sparks.lastPos && n.start <= pos && layout.contains(n.pitch)) {
                val cx = layout.center(n.pitch)
                repeat(9) {
                    val a = Math.toRadians(200.0 + sparks.rnd.nextDouble() * 140.0)
                    val v = 90f + sparks.rnd.nextFloat() * 160f
                    sparks.list.add(Sparks.P(cx, h - 2f, (kotlin.math.cos(a) * v).toFloat(), (kotlin.math.sin(a) * v).toFloat(), lerp(handColor(n.hand), Color.White, 0.4f), now))
                }
            }
        }
        sparks.lastPos = pos
        val it = sparks.list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val t = (now - p.born) / 1e9f
            if (t > 0.45f || sparks.list.size > 400) { it.remove(); continue }
            val px = p.x + p.vx * t
            val py = p.y + p.vy * t + 300f * t * t
            val a = 1f - t / 0.45f
            drawLine(p.color.copy(alpha = a), Offset(px, py), Offset(px - p.vx * 0.03f, py - p.vy * 0.03f), 2f, StrokeCap.Round)
        }

        // count-in
        if (engine.countInRemaining > 0) {
            val beatsPerClick = if (song.beatUnit == 8 && song.beatsPerMeasure % 3 == 0) 1.5 else 4.0 / song.beatUnit
            val remainingClicks = ceil(engine.countInRemaining / beatsPerClick - 1e-6).toInt()
            drawLabel(measurer, "$remainingClicks", Offset(size.width / 2 - 20f, h / 2 - 50f), Color.White.copy(alpha = 0.8f), 64, bold = true)
        }
    }
}

/** Note name at the bottom of a bar: white with a dark outline, centred at [cx], baseline area ending at [bottom]. */
private fun DrawScope.noteName(measurer: TextMeasurer, text: String, cx: Float, bottom: Float, sizeSp: Int, alpha: Float, maxWidth: Float) {
    val style = TextStyle(color = Color.White.copy(alpha = alpha), fontSize = sizeSp.sp, fontWeight = FontWeight.Bold)
    val l = measurer.measure(text, style)
    if (l.size.width > maxWidth + 6) return
    val at = Offset(cx - l.size.width / 2f, bottom - l.size.height)
    val shadow = measurer.measure(text, style.copy(color = Color.Black.copy(alpha = 0.55f * alpha)))
    for ((dx, dy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) drawText(shadow, topLeft = at + Offset(dx, dy))
    drawText(l, topLeft = at)
}

internal fun DrawScope.drawLabel(
    measurer: TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Int,
    bold: Boolean = false, maxWidth: Float = Float.MAX_VALUE,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    if (layout.size.width > maxWidth) return
    drawText(layout, topLeft = at)
}
