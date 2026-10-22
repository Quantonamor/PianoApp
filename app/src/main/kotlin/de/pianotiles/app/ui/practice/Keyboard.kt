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
import androidx.compose.ui.graphics.lerp
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
    /** Keys to let go now / keys to keep holding (see PracticeEngine.holdHints). */
    release: Set<Int> = emptySet(),
    keepHolding: Set<Int> = emptySet(),
    pulse: Float = 0f,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 512) // note names repeat: measure each once
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
        val felt = 5f
        val h = size.height
        val blackH = (h - felt) * 0.62f
        fun activeColor(p: Int): Color? = when {
            p == wrong && p in pressed -> PtColors.Miss
            p in pressed -> Color(0xFFB0BEC5)
            expected.containsKey(p) -> handColor(expected.getValue(p))
            sounding.containsKey(p) -> handColor(sounding.getValue(p))
            else -> null
        }
        // white keys: light body, darker front lip, thin gaps
        for (p in layout.whites) {
            val x = layout.left(p)
            val c = activeColor(p)
            val w = layout.whiteWidth - 1.5f
            val body = if (c == null) Brush.verticalGradient(listOf(Color(0xFFDCDDE3), Color(0xFFF7F7FA), Color(0xFFFFFFFF)), startY = felt, endY = h * 0.9f)
            else Brush.verticalGradient(listOf(lerp(c, Color.White, 0.25f), c), startY = felt, endY = h)
            drawRoundRect(body, Offset(x + 0.75f, felt), Size(w, h - felt), CornerRadius(5f, 5f))
            // front lip
            drawRoundRect(Color.Black.copy(alpha = 0.16f), Offset(x + 0.75f, h - h * 0.06f), Size(w, h * 0.06f), CornerRadius(5f, 5f))
            if (p in heard) drawCircle(PtColors.Hit, radius = layout.whiteWidth * 0.18f, center = Offset(x + layout.whiteWidth / 2, h - h * 0.2f))
            val label = when {
                showNames && naming != NoteNaming.NONE && layout.whiteWidth > 22 -> if (p % 12 == 0) Pitch.name(p, naming) + Pitch.octave(p) else Pitch.name(p, naming)
                p % 12 == 0 -> "C" + Pitch.octave(p) // octave marks like on a real keyboard sticker
                else -> null
            }
            if (label != null) drawLabel(measurer, label, Offset(x + 3f, h - h * 0.06f - 16f), Color(0xFF6A6F86), if (layout.whiteWidth > 30) 10 else 8, maxWidth = layout.whiteWidth - 2)
        }
        // shadow of the felt on the keys
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = felt, endY = felt + 14f), Offset(0f, felt), Size(size.width, 14f))
        // black keys: dark glossy body with a lighter front face
        for (p in range) if (layout.isBlack(p)) {
            val x = layout.left(p)
            val w = layout.blackWidth
            val c = activeColor(p)
            // shadow on the white keys
            drawRoundRect(Color.Black.copy(alpha = 0.28f), Offset(x + 2f, felt), Size(w, blackH + 4f), CornerRadius(3f, 3f))
            val top = c?.let { lerp(it, Color.Black, 0.35f) } ?: Color(0xFF1B1C22)
            val bottom = c ?: Color(0xFF34353F)
            drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = felt, endY = felt + blackH), Offset(x, felt), Size(w, blackH), CornerRadius(3f, 3f))
            // front face (the key's slope towards the player)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.22f)), startY = felt + blackH * 0.86f, endY = felt + blackH),
                Offset(x + w * 0.08f, felt + blackH * 0.86f), Size(w * 0.84f, blackH * 0.14f), CornerRadius(2f, 2f),
            )
            // side highlight
            drawLine(Color.White.copy(alpha = 0.12f), Offset(x + w * 0.18f, felt + 3f), Offset(x + w * 0.18f, felt + blackH * 0.82f), 1.2f)
            if (p in heard) drawCircle(PtColors.Hit, radius = w * 0.25f, center = Offset(x + w / 2, felt + blackH - 14f))
        }
        // hints: let go (white, arrow up) / keep holding (outline in the hit colour), gently pulsing
        for (p in release + keepHolding) if (layout.contains(p)) {
            val x = layout.left(p)
            val w = layout.keyWidth(p)
            val kh = if (layout.isBlack(p)) blackH else h - felt
            val letGo = p in release
            val c = if (letGo) Color.White else PtColors.Hit
            drawRoundRect(c.copy(alpha = 0.25f + 0.25f * pulse), Offset(x, felt), Size(w, kh), CornerRadius(4f, 4f))
            drawRoundRect(c, Offset(x + 1f, felt + 1f), Size(w - 2f, kh - 2f), CornerRadius(4f, 4f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            if (letGo) {
                // arrow up: "lift the finger"
                val cx = x + w / 2; val top = felt + kh * 0.25f; val len = minOf(kh * 0.35f, w * 1.6f)
                drawLine(Color(0xFF0E1220), Offset(cx, top + len), Offset(cx, top), 3f)
                drawLine(Color(0xFF0E1220), Offset(cx, top), Offset(cx - w * 0.25f, top + w * 0.25f), 3f)
                drawLine(Color(0xFF0E1220), Offset(cx, top), Offset(cx + w * 0.25f, top + w * 0.25f), 3f)
            }
        }
        // red felt strip above the keys
        drawRect(Brush.verticalGradient(listOf(Color(0xFF8E1218), Color(0xFF4A070B))), Offset.Zero, Size(size.width, felt))
    }
}
