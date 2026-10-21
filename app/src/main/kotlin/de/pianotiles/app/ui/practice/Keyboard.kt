package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.NoteNaming
import de.pianotiles.core.model.Pitch

/**
 * Piano keyboard. Keys light up in the hand colour when they have to be played, green when they
 * were hit, and show a white dot for notes the microphone currently hears. Multi-touch playable.
 */
@Composable
fun Keyboard(
    range: IntRange,
    expected: Map<Int, Hand>,
    sounding: Map<Int, Hand>,
    heard: Set<Int>,
    pressed: Set<Int>,
    wrong: Int,
    naming: NoteNaming,
    showNames: Boolean,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(range) {
                awaitPointerEventScope {
                    val down = HashMap<PointerId, Int>()
                    while (true) {
                        val event = awaitPointerEvent()
                        val layout = KeyLayout(range.first, range.last, size.width.toFloat())
                        for (c in event.changes) {
                            val key = if (c.pressed) layout.keyAt(c.position.x, c.position.y / size.height) else null
                            val prev = down[c.id]
                            if (key != prev) {
                                prev?.let { onKeyUp(it) }
                                if (key != null) { down[c.id] = key; onKeyDown(key) } else down.remove(c.id)
                            }
                            c.consume()
                        }
                    }
                }
            },
    ) {
        val layout = KeyLayout(range.first, range.last, size.width)
        val h = size.height
        val blackH = h * 0.62f
        fun keyColor(p: Int, base: Color): Color = when {
            p == wrong && p in pressed -> PtColors.Miss
            p in pressed -> Color(0xFFB0BEC5)
            expected.containsKey(p) -> handColor(expected.getValue(p))
            sounding.containsKey(p) -> handColor(sounding.getValue(p)).copy(alpha = 0.85f)
            else -> base
        }
        // white keys
        for (p in layout.whites) {
            val x = layout.left(p)
            val c = keyColor(p, PtColors.WhiteKey)
            drawRoundRect(
                Brush.verticalGradient(listOf(c, if (c == PtColors.WhiteKey) Color(0xFFDADDEA) else c.copy(alpha = 0.8f))),
                Offset(x + 1f, 0f), Size(layout.whiteWidth - 2f, h), CornerRadius(6f, 6f),
            )
            if (showNames && naming != NoteNaming.NONE && (p % 12 == 0 || layout.whiteWidth > 34)) {
                val label = if (p % 12 == 0) Pitch.name(p, naming) + Pitch.octave(p) else Pitch.name(p, naming)
                drawLabel(measurer, label, Offset(x + 4f, h - 26f), Color(0xFF4A5070), if (layout.whiteWidth > 30) 11 else 9, maxWidth = layout.whiteWidth - 4)
            }
            if (p in heard) drawCircle(PtColors.Hit, radius = layout.whiteWidth * 0.18f, center = Offset(x + layout.whiteWidth / 2, h - 40f))
        }
        // black keys
        for (p in range) if (layout.isBlack(p)) {
            val x = layout.left(p)
            val c = keyColor(p, PtColors.BlackKey)
            drawRoundRect(
                Brush.verticalGradient(listOf(c, if (c == PtColors.BlackKey) Color(0xFF3A3F55) else c.copy(alpha = 0.75f))),
                Offset(x, 0f), Size(layout.blackWidth, blackH), CornerRadius(4f, 4f),
            )
            if (p in heard) drawCircle(PtColors.Hit, radius = layout.blackWidth * 0.25f, center = Offset(x + layout.blackWidth / 2, blackH - 16f))
        }
        // top shadow line
        drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent)), Offset.Zero, Size(size.width, 10f))
        drawRect(Color(0x55000000), Offset.Zero, Size(size.width, h), style = Stroke(1f))
    }
}
