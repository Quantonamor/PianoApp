package de.pianotiles.core

import de.pianotiles.core.hands.AutoFingering
import de.pianotiles.core.hands.HandMotion
import de.pianotiles.core.hands.HandRenderer
import de.pianotiles.core.hands.KeyX
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders hands over a drawn keyboard; with -Phands.out=<dir> the images are written for a visual check. */
class HandRenderTest {
    private fun keyboard(img: BufferedImage, lo: Int, hi: Int, keysH: Int) {
        val g = img.createGraphics()
        g.color = java.awt.Color(10, 12, 20); g.fillRect(0, 0, img.width, img.height)
        val whites = (lo..hi).filter { !Pitch.isBlackKey(it) }
        val ww = img.width.toFloat() / whites.size
        whites.forEachIndexed { i, _ -> g.color = java.awt.Color(245, 245, 248); g.fillRect((i * ww).toInt() + 1, 0, ww.toInt() - 2, keysH) }
        for (p in lo..hi) if (Pitch.isBlackKey(p)) {
            val i = whites.indexOf(p - 1)
            g.color = java.awt.Color(25, 26, 32); g.fillRect(((i + 1) * ww - ww * 0.31f).toInt(), 0, (ww * 0.62f).toInt(), (keysH * 0.62f).toInt())
        }
        g.dispose()
    }

    @Test
    fun handsRenderInsideTheirArea() {
        val out = System.getProperty("hands.out")?.let { File(it).apply { mkdirs() } }
        for ((name, song0, beat) in listOf(Triple("ode", DemoSongs.odeToJoy(), 2.5), Triple("scale", DemoSongs.scale(), 3.5), Triple("elise", DemoSongs.furElise(), 2.1))) {
            val song = AutoFingering.apply(song0)
            val lo = (song.lowestPitch / 12) * 12; val hi = (song.highestPitch / 12) * 12 + 11
            val w = 1080; val keysH = 300; val h = keysH + 190
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            keyboard(img, lo, hi, keysH)
            val whites = (lo..hi).count { !Pitch.isBlackKey(it) }
            val ww = w.toFloat() / whites
            val origin = KeyX.of(lo) - 0.5f
            val motion = HandMotion(song)
            val geos = Hand.entries.mapNotNull { hnd -> motion.pose(hnd, beat)?.let { HandRenderer.layout(it, { k -> (k - origin) * ww }, ww, keysH.toFloat(), h.toFloat()) } }
            val scale = 0.4f
            val bw = (w * scale).toInt(); val bh = (h * scale).toInt()
            val buf = IntArray(bw * bh)
            val t0 = System.nanoTime()
            HandRenderer.render(geos, buf, bw, bh, scale, keysH.toFloat())
            val ms = (System.nanoTime() - t0) / 1e6
            println("$name render ${"%.1f".format(ms)} ms")
            assertTrue(buf.any { it ushr 24 > 100 }, "hands visible")
            val hand = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, bw, bh, buf, 0, bw) }
            val g = img.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(hand, 0, 0, w, h, null); g.dispose()
            out?.let { javax.imageio.ImageIO.write(img, "png", File(it, "$name.png")) }
        }
    }
}
