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

    private fun render(name: String, song0: de.pianotiles.core.model.Song, beat: Double, lo0: Int?, hi0: Int?, w: Int, keysH: Int, scale: Float, out: File?) {
        val song = AutoFingering.apply(song0)
        val lo = lo0 ?: (song.lowestPitch / 12) * 12; val hi = hi0 ?: ((song.highestPitch / 12) * 12 + 11)
        val h = keysH + (keysH * 0.6).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        keyboard(img, lo, hi, keysH)
        val whites = (lo..hi).count { !Pitch.isBlackKey(it) }
        val ww = w.toFloat() / whites
        val origin = KeyX.of(lo) - 0.5f
        val motion = HandMotion(song)
        val geos = Hand.entries.mapNotNull { hnd -> motion.pose(hnd, beat)?.let { HandRenderer.layout(it, { k -> (k - origin) * ww }, ww, keysH.toFloat(), h.toFloat()) } }
        val bw = (w * scale).toInt(); val bh = (h * scale).toInt()
        val buf = IntArray(bw * bh)
        repeat(20) { HandRenderer.render(geos, buf, bw, bh, scale, keysH.toFloat()) } // warm up
        val t0 = System.nanoTime()
        repeat(50) { HandRenderer.render(geos, buf, bw, bh, scale, keysH.toFloat()) }
        println("$name render ${"%.2f".format((System.nanoTime() - t0) / 1e6 / 50)} ms per frame (${bw}x$bh)")
        assertTrue(buf.any { it ushr 24 > 100 }, "hands visible")
        val hand = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, bw, bh, buf, 0, bw) }
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(hand, 0, 0, w, h, null); g.dispose()
        out?.let { javax.imageio.ImageIO.write(img, "png", File(it, "$name.png")) }
    }

    @Test
    fun handsRenderInsideTheirArea() {
        val out = System.getProperty("hands.out")?.let { File(it).apply { mkdirs() } }
        // as in the app (40 % resolution) …
        render("ode", DemoSongs.odeToJoy(), 2.5, null, null, 1080, 300, 0.4f, out)
        render("scale", DemoSongs.scale(), 3.5, null, null, 1080, 300, 0.4f, out)
        render("elise", DemoSongs.furElise(), 2.1, null, null, 1080, 300, 0.4f, out)
        // … and a close-up at full resolution to judge the details
        render("closeup", DemoSongs.odeToJoy(), 0.5, 57, 74, 1080, 420, 1f, out)
        render("closeup_left", DemoSongs.odeToJoy(), 0.5, 40, 57, 1080, 420, 1f, out)
        render("closeup_black", DemoSongs.furElise(), 0.3, 62, 79, 1080, 420, 1f, out)
        // proportions of the app in portrait (Ode: 15 white keys on a 1080 px phone, keys 5.2 key widths long)
        render("app_portrait", DemoSongs.odeToJoy(), 2.5, 48, 72, 1080, (1080 / 15 * 5.2).toInt(), 0.4f, out)
        render("app_portrait_50", DemoSongs.odeToJoy(), 2.5, 48, 72, 1080, (1080 / 15 * 5.2).toInt(), 0.5f, out)
        // a film strip of the motion (right hand, Ode: E E F G G F E D C), for checking smoothness and key presses
        if (out != null) {
            strip(out, "strip", DemoSongs.odeToJoy(), (0 until 8).map { k -> 2.0 + k * 0.5 }, 59, 69)
            // thumb passing under in the C major scale (C D E | F: thumb under the third finger)
            strip(out, "strip_scale", DemoSongs.scale(), (0 until 8).map { k -> 1.5 + k * 0.25 }, 59, 74)
        }
    }

    private fun strip(out: File, name: String, song: de.pianotiles.core.model.Song, frames: List<Double>, lo: Int, hi: Int) {
        run {
            val tiles = frames.map { b -> render("tmp_strip", song, b, lo, hi, 540, 220, 0.6f, out); javax.imageio.ImageIO.read(File(out, "tmp_strip.png")) }
            val strip = BufferedImage(tiles[0].width * 4, tiles[0].height * 2, BufferedImage.TYPE_INT_ARGB)
            val g = strip.createGraphics()
            tiles.forEachIndexed { k, t -> g.drawImage(t, (k % 4) * t.width, (k / 4) * t.height, null); g.color = java.awt.Color.RED; g.drawString("beat ${frames[k]}", (k % 4) * t.width + 6, (k / 4) * t.height + 14) }
            g.dispose()
            javax.imageio.ImageIO.write(strip, "png", File(out, "$name.png"))
            File(out, "tmp_strip.png").delete()
        }
    }
}
