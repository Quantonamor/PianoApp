package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.rememberTextMeasurer
import de.pianotiles.core.hands.HandMotion
import de.pianotiles.core.hands.HandPose
import de.pianotiles.core.hands.KeyX
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch
import kotlin.math.sqrt

/**
 * Two shaded hands lying on the keyboard, seen from the player's seat: the palm comes from the bottom edge, the
 * fingertips rest on the keys they play (further up on black keys). A finger that plays sinks into the key and its
 * tip lights up in the hand colour; small numbers on the nails show the fingering. Draws nothing (and takes no
 * touches) where there is no hand.
 */
@Composable
fun HandsOverlay(motion: HandMotion, beat: Double, frame: Long, range: IntRange, showNumbers: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    Canvas(modifier.fillMaxSize().clipToBounds()) {
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
        val y = h * (if (black) 0.36f else 0.70f) + h * 0.05f * pose.down[f]
        Offset(px(pose.x[f]), y)
    }
    // knuckles: a bit closer together than the tips, middle finger furthest forward
    val centre = (tips[1].x + tips[2].x + tips[3].x + tips[4].x) / 4f
    val knuckleY = floatArrayOf(1.30f, 1.02f, 0.98f, 1.01f, 1.07f)
    val knuckles = Array(5) { f ->
        if (f == 0) Offset(tips[1].x + (centre - tips[1].x) * 0.3f - dir * fingerW * 0.9f, h * knuckleY[0])
        else Offset(centre + (tips[f].x - centre) * 0.8f, h * knuckleY[f])
    }

    // palm: from the knuckles down past the bottom edge, with a soft shadow on the keys
    val palm = Path().apply {
        val left = minOf(knuckles[1].x, knuckles[4].x) - fingerW * 0.6f
        val right = maxOf(knuckles[1].x, knuckles[4].x) + fingerW * 0.6f
        moveTo(left, h * 1.4f)
        lineTo(left, knuckles[if (dir > 0) 1 else 4].y)
        cubicTo(left, h * 0.9f, right, h * 0.9f, right, knuckles[if (dir > 0) 4 else 1].y)
        lineTo(right, h * 1.4f)
        close()
    }
    translate(left = fingerW * 0.12f, top = fingerW * 0.18f) { drawPath(palm, Color.Black.copy(alpha = 0.22f)) }
    drawPath(palm, Brush.verticalGradient(listOf(light, dark), startY = h * 0.85f, endY = h * 1.2f))

    // fingers: thumb first (it lies lowest), then from the outside in so the middle finger is on top
    for (f in intArrayOf(0, 4, 1, 3, 2)) {
        val w = fingerW * (if (f == 0) 1.15f else if (f == 4) 0.85f else 1f)
        drawFinger(knuckles[f], tips[f], w, light, dark, tint, pose.down[f])
        if (showNumbers) {
            val n = "${f + 1}"
            label(n, Offset(tips[f].x - w * 0.18f, tips[f].y + w * 0.15f), Color(0xCC3A2A20))
        }
    }
}

/** One finger as a tapered, cylinder-shaded tube with a nail; [down] makes the tip glow. */
private fun DrawScope.drawFinger(base: Offset, tip: Offset, width: Float, light: Color, dark: Color, glow: Color, down: Float) {
    val dx = tip.x - base.x
    val dy = tip.y - base.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val wb = width / 2f
    val wt = width * 0.42f // perspective: the tip is further away and narrower
    val path = Path().apply {
        moveTo(base.x + nx * wb, base.y + ny * wb)
        lineTo(tip.x + nx * wt, tip.y + ny * wt)
        // rounded tip
        cubicTo(
            tip.x + nx * wt + dx / len * wt * 1.3f, tip.y + ny * wt + dy / len * wt * 1.3f,
            tip.x - nx * wt + dx / len * wt * 1.3f, tip.y - ny * wt + dy / len * wt * 1.3f,
            tip.x - nx * wt, tip.y - ny * wt,
        )
        lineTo(base.x - nx * wb, base.y - ny * wb)
        close()
    }
    // shadow on the keys
    translate(left = width * 0.12f, top = width * 0.2f) { drawPath(path, Color.Black.copy(alpha = 0.25f)) }
    // cylinder shading across the finger
    val mid = Offset((base.x + tip.x) / 2, (base.y + tip.y) / 2)
    drawPath(
        path,
        Brush.linearGradient(
            0f to dark, 0.35f to light, 0.55f to light, 1f to dark,
            start = Offset(mid.x - nx * wb, mid.y - ny * wb), end = Offset(mid.x + nx * wb, mid.y + ny * wb),
        ),
    )
    // joints
    for (t in floatArrayOf(0.38f, 0.66f)) {
        val c = Offset(base.x + dx * t, base.y + dy * t)
        val w = wb + (wt - wb) * t
        drawLine(dark.copy(alpha = 0.45f), Offset(c.x + nx * w * 0.6f, c.y + ny * w * 0.6f), Offset(c.x - nx * w * 0.6f, c.y - ny * w * 0.6f), 1.2f)
    }
    // nail
    val nail = Offset(tip.x - dx / len * wt * 0.4f, tip.y - dy / len * wt * 0.4f)
    drawOval(Color.White.copy(alpha = 0.45f), Offset(nail.x - wt * 0.55f, nail.y - wt * 0.7f), Size(wt * 1.1f, wt * 1.3f))
    if (down > 0f) {
        drawCircle(glow.copy(alpha = 0.55f * down), radius = wt * 1.6f, center = Offset(tip.x, tip.y - wt * 0.3f))
    }
}
