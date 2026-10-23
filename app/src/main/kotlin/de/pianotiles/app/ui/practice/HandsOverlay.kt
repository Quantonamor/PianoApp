package de.pianotiles.app.ui.practice

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Renders the hands on its own thread, so the 3D rendering never holds up the screen: the UI hands in the
 * newest pose ([request]) and draws the newest finished picture ([latest]); three bitmaps rotate, so the one
 * being drawn is never the one being written.
 */
private class HandRenderLoop(private val onFrame: () -> Unit) {
    private class Request(val geos: List<HandRenderer.Geometry>, val w: Int, val h: Int, val scale: Float, val keysHeight: Float)

    private val lock = Object()
    private var pending: Request? = null
    @Volatile private var running = true
    @Volatile var latest: ImageBitmap? = null
        private set
    @Volatile var latestSize = IntSize.Zero
        private set
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private val images = arrayOfNulls<ImageBitmap>(3)
    private var pixels = IntArray(0)
    private var next = 0
    private var count = 0

    private val thread = Thread({
        while (running) {
            val r = synchronized(lock) {
                while (running && pending == null) lock.wait()
                pending.also { pending = null }
            } ?: continue
            runCatching { render(r) }.onFailure { android.util.Log.e("HandRender", "rendering failed", it) }
        }
        android.util.Log.i("HandRender", "render thread stopped")
    }, "HandRender").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true; start() }

    fun request(geos: List<HandRenderer.Geometry>, w: Int, h: Int, scale: Float, keysHeight: Float) {
        synchronized(lock) { pending = Request(geos, w, h, scale, keysHeight); lock.notify() }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notify() }
    }

    private fun render(r: Request) {
        val i = next
        if (bitmaps[i]?.width != r.w || bitmaps[i]?.height != r.h) {
            bitmaps[i] = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888)
            images[i] = bitmaps[i]!!.asImageBitmap()
        }
        if (pixels.size != r.w * r.h) pixels = IntArray(r.w * r.h)
        val t0 = System.nanoTime()
        HandRenderer.render(r.geos, pixels, r.w, r.h, r.scale, r.keysHeight)
        // how long a picture takes on this device (logged now and then)
        if (count++ % 60 == 0) android.util.Log.i("HandRender", "${r.w}x${r.h}: ${(System.nanoTime() - t0) / 1_000_000} ms")
        bitmaps[i]!!.setPixels(pixels, 0, r.w, 0, 0, r.w, r.h)
        latestSize = IntSize(r.w, r.h)
        latest = images[i]
        next = (i + 1) % 3
        onFrame()
    }
}

/**
 * Two realistic, half-transparent 3D hands seen from the player's seat (rigged hand model, see core
 * `HandModel.kt`). The canvas covers the keyboard ([keysHeight]) and a strip below it where the palms rest, so only
 * the fingers reach onto the keys. The picture is rendered at 50 % resolution on a background thread and drawn
 * scaled; small numbers on the fingertips show the fingering.
 */
@Composable
fun HandsOverlay(
    motion: HandMotion, beat: Double, frame: Long, range: IntRange, showNumbers: Boolean, keysHeight: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    // a finished picture must be drawn even when nothing else redraws the screen (paused): count it as a frame
    var rendered by remember { mutableIntStateOf(0) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val loop = remember { HandRenderLoop { main.post { rendered++ } } }
    val lastKey = remember { longArrayOf(Long.MIN_VALUE) }
    DisposableEffect(loop) { onDispose { loop.stop() } }
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        frame.also { } // redraw every frame …
        rendered.also { } // … and when the background thread has finished a picture
        val layout = KeyLayout(range.first, range.last, size.width)
        val firstWhite = layout.whites.firstOrNull() ?: return@Canvas
        val origin = KeyX.of(firstWhite) - 0.5f
        val keyX = { k: Float -> (k - origin) * layout.whiteWidth }
        val geos = Hand.entries.mapNotNull { hand ->
            motion.pose(hand, beat)?.takeIf { it.active }?.let { HandRenderer.layout(it, keyX, layout.whiteWidth, keysHeight, size.height) }
        }
        if (geos.isEmpty()) return@Canvas
        val scale = 0.5f
        val bw = (size.width * scale).toInt().coerceAtLeast(1)
        val bh = (size.height * scale).toInt().coerceAtLeast(1)
        // only a changed pose is rendered again (a finished picture redraws the canvas, which must not loop)
        val key = geos.fold(31L * bw + bh) { acc, g ->
            var h = acc * 31 + g.hand.ordinal
            for (t in g.tips) h = (h * 31 + t[0].toBits()) * 31 + t[1].toBits()
            for (d in g.down) h = h * 31 + d.toBits()
            h
        }
        if (key != lastKey[0]) { lastKey[0] = key; loop.request(geos, bw, bh, scale, keysHeight) }
        val img = loop.latest
        val sz = loop.latestSize
        if (img != null && sz.width == bw && sz.height == bh) drawImage(
            img, srcOffset = IntOffset.Zero, srcSize = sz,
            dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low,
        )
        if (showNumbers) for (g in geos) for (f in 0 until 5) {
            val t = g.tips[f]
            drawLabel(measurer, "${f + 1}", Offset(t[0] - g.fingerWidth * 0.18f, t[1] - g.fingerWidth * 0.95f), Color(0xFF5A3A2A),
                if (layout.whiteWidth > 30) 11 else 9, bold = true)
        }
    }
}
