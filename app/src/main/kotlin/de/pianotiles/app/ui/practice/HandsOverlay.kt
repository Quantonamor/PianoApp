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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.rememberTextMeasurer
import de.pianotiles.core.hands.HandMotion
import de.pianotiles.core.hands.HandPose
import de.pianotiles.core.hands.KeyX
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch

/**
 * Two shaded hands lying on the keyboard, seen from the player's seat: the palm comes from the bottom edge, the
 * fingertips rest on the keys they play (further up on black keys). A finger that plays sinks into the key and its
 * tip lights up in the hand colour; small numbers on the nails show the fingering.
 *
 * Drawn only from simple shapes (round-capped lines, rounded rectangles, circles) with plain or vertical
 * gradients: free paths with angled gradients crashed the emulator's software renderer.
 */
@Composable
fun HandsOverlay(motion: HandMotion, beat: Double, frame: Long, range: IntRange, showNumbers: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Canvas(modifier.fillMaxSize()) {
        frame.also { } // redraw every frame
        val layout = KeyLayout(range.first, range.last, size.width)
        val firstWhite = layout.whites.firstOrNull() ?: return@Canvas
        val origin = KeyX.of(firstWhite) - 0.5f
        fun px(keyX: Float) = (keyX - origin) * layout.whiteWidth
        for (hand in Hand.entries) {
            val pose = motion.pose(hand, beat) ?: continue
            if (!pose.active) continue
            drawHand(pose, ::px, layout.whiteWidth, showNumbers) { text, at, color ->
                drawLabel(measurer, text, at, color, if (layout.whiteWidth > 30) 11 else 9, bold = true)
            }
        }
    }
}

private val Skin = Color(0xFFF2D2B6)
private val SkinShade = Color(0xFFC99A7C)

private fun DrawScope.drawHand(
    pose: HandPose, px: (Float) -> Float, keyW: Float, showNumbers: Boolean,
    label: (String, Offset, Color) -> Unit,
) {
    val h = size.height
    val dir = if (pose.hand == Hand.RIGHT) 1f else -1f
    val tint = handColor(pose.hand)
    val light = lerp(Skin, tint, 0.18f)
    val dark = lerp(SkinShade, handColorDark(pose.hand), 0.25f)
    val fingerW = (keyW * 0.62f).coerceIn(8f, 40f)

    // fingertips: on the front of white keys, between the black keys for black ones; pressing pulls them in
    val tips = Array(5) { f ->
        val black = pose.pitch[f] >= 0 && Pitch.isBlackKey(pose.pitch[f])
        Offset(px(pose.x[f]), h * (if (black) 0.36f else 0.70f) + h * 0.05f * pose.down[f])
    }
    // knuckles: a bit closer together than the tips, middle finger furthest forward
    val centre = (tips[1].x + tips[2].x + tips[3].x + tips[4].x) / 4f
    val knuckleY = floatArrayOf(1.0f, 0.97f, 0.95f, 0.97f, 1.0f)
    val knuckles = Array(5) { f ->
        if (f == 0) Offset(tips[1].x + (centre - tips[1].x) * 0.3f - dir * fingerW * 0.9f, h * 1.0f)
        else Offset(centre + (tips[f].x - centre) * 0.8f, h * knuckleY[f])
    }

    // palm: a rounded block from the knuckles down to the bottom edge
    val left = minOf(knuckles[1].x, knuckles[4].x) - fingerW * 0.6f
    val right = maxOf(knuckles[1].x, knuckles[4].x) + fingerW * 0.6f
    val top = h * 0.9f
    if (top < h) {
        drawRoundRect(Color.Black.copy(alpha = 0.2f), Offset(left + fingerW * 0.12f, top + fingerW * 0.15f), Size(right - left, h - top), CornerRadius(fingerW * 0.6f))
        drawRoundRect(
            Brush.verticalGradient(listOf(light, dark), startY = top, endY = h),
            Offset(left, top), Size(right - left, h - top + fingerW), CornerRadius(fingerW * 0.6f),
        )
    }

    // fingers: thumb first (it lies lowest), then from the outside in so the middle finger is on top
    for (f in intArrayOf(0, 4, 1, 3, 2)) {
        val w = fingerW * (if (f == 0) 1.15f else if (f == 4) 0.85f else 1f)
        drawFinger(knuckles[f], tips[f], w, light, dark, tint, pose.down[f])
        if (showNumbers) label("${f + 1}", Offset(tips[f].x - w * 0.2f, tips[f].y - w * 0.15f), Color(0xCC3A2A20))
    }
}

/** One finger as a round-capped tube: shadow, darker rim, light core and highlight give a cylinder look. */
private fun DrawScope.drawFinger(base: Offset, tip: Offset, width: Float, light: Color, dark: Color, glow: Color, down: Float) {
    val shadow = Offset(width * 0.12f, width * 0.2f)
    drawLine(Color.Black.copy(alpha = 0.22f), base + shadow, tip + shadow, width, StrokeCap.Round)
    drawLine(dark, base, tip, width, StrokeCap.Round)
    drawLine(light, base, tip, width * 0.7f, StrokeCap.Round)
    // highlight slightly to one side
    val hl = Offset(-width * 0.12f, 0f)
    drawLine(Color.White.copy(alpha = 0.3f), base + hl, tip + hl, width * 0.22f, StrokeCap.Round)
    // nail
    drawCircle(Color.White.copy(alpha = 0.4f), radius = width * 0.26f, center = tip)
    if (down > 0f) drawCircle(glow.copy(alpha = 0.55f * down), radius = width * 0.75f, center = tip)
}
