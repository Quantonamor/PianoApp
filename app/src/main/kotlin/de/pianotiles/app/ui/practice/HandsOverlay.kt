package de.pianotiles.app.ui.practice

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import de.pianotiles.core.hands.HandMotion
import de.pianotiles.core.hands.HandRenderer
import de.pianotiles.core.hands.KeyX
import de.pianotiles.core.model.Hand

/** Reused pixel buffer + bitmap for the hands (one per screen). */
private class HandCanvas {
    var bitmap: Bitmap? = null
    var image: ImageBitmap? = null
    var pixels = IntArray(0)

    fun ensure(w: Int, h: Int) {
        if (bitmap?.width == w && bitmap?.height == h) return
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        image = bitmap!!.asImageBitmap()
        pixels = IntArray(w * h)
    }
}

/**
 * Two realistic, half-transparent hands seen from the player's seat ([HandRenderer]: domed palm, fingers with
 * three phalanges, nails, light and shadow). The canvas covers the keyboard ([keysHeight]) and a strip below it
 * where the palms rest, so only the fingers reach onto the keys. The image is rendered at 40 % resolution and
 * drawn scaled; small numbers on the nails show the fingering.
 */
@Composable
fun HandsOverlay(
    motion: HandMotion, beat: Double, frame: Long, range: IntRange, showNumbers: Boolean, keysHeight: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val buffer = remember { HandCanvas() }
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        frame.also { } // redraw every frame
        val layout = KeyLayout(range.first, range.last, size.width)
        val firstWhite = layout.whites.firstOrNull() ?: return@Canvas
        val origin = KeyX.of(firstWhite) - 0.5f
        val keyX = { k: Float -> (k - origin) * layout.whiteWidth }
        val geos = Hand.entries.mapNotNull { hand ->
            motion.pose(hand, beat)?.takeIf { it.active }?.let { HandRenderer.layout(it, keyX, layout.whiteWidth, keysHeight, size.height) }
        }
        if (geos.isEmpty()) return@Canvas
        val scale = 0.4f
        val bw = (size.width * scale).toInt().coerceAtLeast(1)
        val bh = (size.height * scale).toInt().coerceAtLeast(1)
        buffer.ensure(bw, bh)
        HandRenderer.render(geos, buffer.pixels, bw, bh, scale, keysHeight)
        buffer.bitmap!!.setPixels(buffer.pixels, 0, bw, 0, 0, bw, bh)
        drawImage(
            buffer.image!!, srcOffset = IntOffset.Zero, srcSize = IntSize(bw, bh),
            dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low,
        )
        if (showNumbers) for (g in geos) for (f in 0 until 5) {
            val t = g.tips[f]
            drawLabel(measurer, "${f + 1}", Offset(t[0] - g.fingerWidth * 0.18f, t[1] - g.fingerWidth * 0.95f), Color(0xFF5A3A2A),
                if (layout.whiteWidth > 30) 11 else 9, bold = true)
        }
    }
}
