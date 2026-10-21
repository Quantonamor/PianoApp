package de.pianotiles.app.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import de.pianotiles.core.io.ImportException
import de.pianotiles.core.io.MidiImporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.model.Song
import de.pianotiles.core.omr.GrayImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ScoreType { PDF, MIDI, MUSICXML, UNKNOWN }

class LoadedFile(val name: String, val bytes: ByteArray) {
    val baseName: String get() = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Neues Stück" }
    val type: ScoreType
        get() = when {
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> ScoreType.PDF
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MThd" -> ScoreType.MIDI
            MusicXmlImporter.isZip(bytes) -> ScoreType.MUSICXML
            String(bytes, 0, minOf(bytes.size, 600), Charsets.UTF_8).let { it.contains("<score-partwise") || it.contains("<?xml") } -> ScoreType.MUSICXML
            else -> ScoreType.UNKNOWN
        }
}

/** Rendered PDF pages: full-resolution grayscale for recognition, small bitmaps for the preview. */
class RenderedPdf(val pages: List<GrayImage>, val previews: List<Bitmap>, val scale: Float)

object ScoreImporter {
    /** Target page width for recognition: gives ~16–20 px per staff space for typical A4 scores. */
    const val RENDER_WIDTH = 2000
    const val PREVIEW_WIDTH = 1000
    const val MAX_PAGES = 30

    suspend fun read(context: Context, uri: Uri): LoadedFile = withContext(Dispatchers.IO) {
        var name = "Neues Stück"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportException("Datei konnte nicht geöffnet werden")
        LoadedFile(name, bytes)
    }

    suspend fun importDirect(file: LoadedFile): Song = withContext(Dispatchers.Default) {
        when (file.type) {
            ScoreType.MIDI -> MidiImporter.import(file.bytes, file.baseName)
            ScoreType.MUSICXML -> MusicXmlImporter.import(file.bytes, file.baseName)
            else -> throw ImportException("Unbekanntes Dateiformat. Unterstützt: PDF, MIDI (.mid), MusicXML (.xml, .musicxml, .mxl)")
        }
    }

    suspend fun renderPdf(context: Context, bytes: ByteArray, progress: (Int, Int) -> Unit): RenderedPdf = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import.pdf")
        tmp.writeBytes(bytes)
        val pages = ArrayList<GrayImage>()
        val previews = ArrayList<Bitmap>()
        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PAGES)
                if (count == 0) throw ImportException("Das PDF enthält keine Seiten")
                for (i in 0 until count) {
                    progress(i, count)
                    renderer.openPage(i).use { page ->
                        val w = RENDER_WIDTH
                        val h = (w.toDouble() * page.height / page.width).toInt().coerceAtMost(w * 3)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        pages.add(GrayImage.fromArgb(w, h, px))
                        previews.add(Bitmap.createScaledBitmap(bmp, PREVIEW_WIDTH, h * PREVIEW_WIDTH / w, true))
                        bmp.recycle()
                    }
                }
            }
        }
        tmp.delete()
        RenderedPdf(pages, previews, PREVIEW_WIDTH.toFloat() / RENDER_WIDTH)
    }
}
