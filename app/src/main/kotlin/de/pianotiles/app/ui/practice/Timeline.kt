package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Song
import de.pianotiles.core.practice.LoopSettings

/**
 * Overview of the whole piece: one column per measure (height = number of notes, red tint =
 * mistakes made there), the loop range and the play head.
 *
 * Tap = jump to a measure. Drag = select a loop range (snaps to whole measures).
 */
@Composable
fun Timeline(
    song: Song,
    position: Double,
    loop: LoopSettings,
    mistakes: Map<Int, Int>,
    onSeekMeasure: (Int) -> Unit,
    onSelectLoop: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = song.measureCount
    val density = remember(song) {
        IntArray(count).also { arr -> song.notes.forEach { n -> arr[song.measureIndexAt(n.start).coerceIn(0, count - 1)]++ } }
    }
    val maxDensity = remember(density) { (density.maxOrNull() ?: 1).coerceAtLeast(1) }
    var dragStart by remember { mutableIntStateOf(-1) }
    Canvas(
        modifier
            .pointerInput(count) {
                detectTapGestures { o -> onSeekMeasure((o.x / size.width * count).toInt().coerceIn(0, count - 1)) }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragStart = (o.x / size.width * count).toInt().coerceIn(0, count - 1) },
                    onDragEnd = { dragStart = -1 },
                    onDragCancel = { dragStart = -1 },
                ) { change, _ ->
                    change.consume()
                    val cur = (change.position.x / size.width * count).toInt().coerceIn(0, count - 1)
                    if (dragStart >= 0) onSelectLoop(minOf(dragStart, cur), maxOf(dragStart, cur))
                }
            },
    ) {
        val w = size.width; val h = size.height
        val mw = w / count
        drawRoundRect(PtColors.SurfaceHigh, Offset.Zero, Size(w, h), CornerRadius(10f))
        for (m in 0 until count) {
            val bh = (h - 10) * density[m] / maxDensity
            val err = mistakes[m] ?: 0
            val c = if (err > 0) PtColors.Miss.copy(alpha = (0.35f + 0.15f * err).coerceAtMost(0.9f)) else Color(0xFF5C6694)
            drawRect(c, Offset(m * mw + mw * 0.15f, h - 5 - bh), Size(mw * 0.7f, bh))
        }
        if (loop.endBeat > loop.startBeat) {
            val a = song.measureIndexAt(loop.startBeat)
            val b = song.measureIndexAt(loop.endBeat - 1e-6)
            val color = if (loop.enabled) PtColors.Loop else PtColors.Loop.copy(alpha = 0.35f)
            drawRoundRect(color.copy(alpha = if (loop.enabled) 0.3f else 0.12f), Offset(a * mw, 0f), Size((b - a + 1) * mw, h), CornerRadius(8f))
            drawRect(color, Offset(a * mw, 0f), Size(3f, h))
            drawRect(color, Offset((b + 1) * mw - 3f, 0f), Size(3f, h))
        }
        val len = song.measureStart(count).coerceAtLeast(1e-6)
        val px = (position.coerceIn(0.0, len) / len * w).toFloat()
        drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, h))
    }
}
