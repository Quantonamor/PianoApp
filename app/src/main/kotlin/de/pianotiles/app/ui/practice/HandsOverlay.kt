package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.rememberTextMeasurer
import de.pianotiles.core.hands.HandMotion
import de.pianotiles.core.hands.HandPose
import de.pianotiles.core.hands.KeyX
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch
import kotlin.math.sqrt

/**
 * Two half-transparent, shaded hands seen from the player's seat. The canvas covers the keyboard ([keysHeight]) and a
 * strip below it where the palms rest, so only the fingers reach onto the keys and the keys stay visible. Every
 * finger has three rounded segments with knuckles, a rim shadow, a light core and a highlight; the finger that plays
 * sinks into its key and its tip glows in the hand colour. Small numbers on the nails show the fingering.
 * Drawn from simple shapes only (round-capped lines, circles, rounded rectangles): free paths with angled gradients
 * crashed the emulator's software renderer.
 */
@Composable
fun HandsOverlay(
    motion: HandMotion, beat: Double, frame: Long, range: IntRange, showNumbers: Boolean, keysHeight: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Canvas(
        modifier.fillMaxSize().clipToBounds()
            // the whole hand fades as one piece (overlapping fingers do not get darker)
            .graphicsLayer { alpha = 0.62f; compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        frame.also { } // redraw every frame
        val layout = KeyLayout(range.first, range.last, size.width)
        val firstWhite = layout.whites.firstOrNull() ?: return@Canvas
        val origin = KeyX.of(firstWhite) - 0.5f
        fun px(keyX: Float) = (keyX - origin) * layout.whiteWidth
        for (hand in Hand.entries) {
            val pose = motion.pose(hand, beat) ?: continue
            if (!pose.active) continue
            drawHand(pose, ::px, layout.whiteWidth, keysHeight, showNumbers) { text, at ->
                drawLabel(measurer, text, at, Color(0xFF3A2A20), if (layout.whiteWidth > 30) 11 else 9, bold = true)
            }
        }
    }
}

private val Skin = Color(0xFFF3D3B8)
private val SkinShade = Color(0xFFB98468)

private fun DrawScope.drawHand(
    pose: HandPose, px: (Float) -> Float, keyW: Float, keysH: Float, showNumbers: Boolean,
    label: (String, Offset) -> Unit,
) {
    val h = size.height
    val zone = (h - keysH).coerceAtLeast(keysH * 0.25f)
    val dir = if (pose.hand == Hand.RIGHT) 1f else -1f
    val tint = handColor(pose.hand)
    val light = lerp(Skin, tint, 0.10f)
    val dark = lerp(SkinShade, handColorDark(pose.hand), 0.15f)
    val fingerW = (keyW * 0.48f).coerceIn(7f, 30f)

    // fingertips on the keys: front part of white keys, middle of black keys; pressing pulls them in a little
    val tips = Array(5) { f ->
        val black = pose.pitch[f] >= 0 && Pitch.isBlackKey(pose.pitch[f])
        Offset(px(pose.x[f]), keysH * (if (black) 0.42f else 0.78f) + keysH * 0.05f * pose.down[f])
    }
    // knuckles in the strip below the keys; middle finger furthest forward
    val centre = (tips[1].x + tips[2].x + tips[3].x + tips[4].x) / 4f
    val knuckleY = floatArrayOf(0f, 0.16f, 0.10f, 0.16f, 0.26f)
    val knuckles = Array(5) { f ->
        if (f == 0) Offset(tips[1].x + (centre - tips[1].x) * 0.2f - dir * fingerW * 1.6f, keysH + zone * 0.55f)
        else Offset(centre + (tips[f].x - centre) * 0.85f, keysH + zone * knuckleY[f])
    }

    // palm: rounded block below the knuckles with a soft shadow and a highlight
    val left = minOf(knuckles[1].x, knuckles[4].x) - fingerW * 0.8f
    val right = maxOf(knuckles[1].x, knuckles[4].x) + fingerW * 0.8f
    val top = keysH + zone * 0.05f
    val palmH = h - top + fingerW * 2
    drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(left + fingerW * 0.2f, top + fingerW * 0.3f), Size(right - left, palmH), CornerRadius(fingerW * 1.4f))
    drawRoundRect(Brush.verticalGradient(listOf(light, dark), startY = top, endY = h), Offset(left, top), Size(right - left, palmH), CornerRadius(fingerW * 1.4f))
    drawOval(Color.White.copy(alpha = 0.18f), Offset(left + (right - left) * 0.2f, top + zone * 0.12f), Size((right - left) * 0.5f, zone * 0.35f))

    // fingers: thumb first, then from the outside in so the middle finger lies on top
    for (f in intArrayOf(0, 4, 1, 3, 2)) {
        val w = fingerW * (if (f == 0) 1.2f else if (f == 4) 0.85f else 1f)
        drawFinger(knuckles[f], tips[f], w, light, dark, tint, pose.down[f], thumb = f == 0, dir = dir)
        if (showNumbers) label("${f + 1}", Offset(tips[f].x - w * 0.22f, tips[f].y - w * 0.95f))
    }
}

/** A finger in three segments (getting thinner towards the tip) with knuckles, rim shadow, light core and highlight. */
private fun DrawScope.drawFinger(
    base: Offset, tip: Offset, width: Float, light: Color, dark: Color, glow: Color, down: Float, thumb: Boolean, dir: Float,
) {
    val dx = tip.x - base.x
    val dy = tip.y - base.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    // a slight arch: the middle joint is lifted sideways a little (fingers are curved over the keys)
    val nx = -dy / len; val ny = dx / len
    val bend = len * 0.04f * (if (thumb) -dir else 1f)
    val j1 = Offset(base.x + dx * 0.45f + nx * bend, base.y + dy * 0.45f + ny * bend)
    val j2 = Offset(base.x + dx * 0.76f + nx * bend * 0.5f, base.y + dy * 0.76f + ny * bend * 0.5f)
    val segs = listOf(Triple(base, j1, width), Triple(j1, j2, width * 0.9f), Triple(j2, tip, width * 0.8f))
    val shadow = Offset(width * 0.18f, width * 0.28f)
    for ((a, b, w) in segs) drawLine(Color.Black.copy(alpha = 0.28f), a + shadow, b + shadow, w, StrokeCap.Round)
    for ((a, b, w) in segs) drawLine(dark, a, b, w, StrokeCap.Round)
    for ((a, b, w) in segs) drawLine(light, a, b, w * 0.7f, StrokeCap.Round)
    // knuckle bumps
    for (j in listOf(j1, j2)) drawCircle(lerp(light, Color.White, 0.15f), radius = width * 0.36f, center = j)
    // highlight along the upper side
    val hl = Offset(-width * 0.16f, -width * 0.04f)
    for ((a, b, w) in segs) drawLine(Color.White.copy(alpha = 0.38f), a + hl, b + hl, w * 0.18f, StrokeCap.Round)
    // nail
    val nail = Offset(tip.x - dx / len * width * 0.3f, tip.y - dy / len * width * 0.3f)
    drawRoundRect(Color(0xFFF7E3DA), Offset(nail.x - width * 0.26f, nail.y - width * 0.32f), Size(width * 0.52f, width * 0.5f), CornerRadius(width * 0.2f))
    if (down > 0f) drawCircle(glow.copy(alpha = 0.7f * down), radius = width * 0.7f, center = tip)
}
