package de.pianotiles.app.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import de.pianotiles.app.i18n.tr
import de.pianotiles.core.io.AbcImporter
import de.pianotiles.core.io.ImportException
import de.pianotiles.core.io.MeiImporter
import de.pianotiles.core.io.MidiImporter
import de.pianotiles.core.io.MuseScoreImporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.library.SongPackage
import de.pianotiles.core.model.Song
import de.pianotiles.core.omr.GrayImage
import de.pianotiles.core.omr.PhotoPrep
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ScoreType { PDF, MIDI, MUSICXML, ABC, MUSESCORE, MEI, IMAGE, PACKAGE, UNKNOWN }

class LoadedFile(val name: String, val bytes: ByteArray) {
    val baseName: String get() = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { tr("Neues Stück") }
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val type: ScoreType by lazy { detect() }

    private fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { (bytes[it].toInt() and 0xFF) == b[it] }

    private fun detect(): ScoreType {
        val head = String(bytes, 0, minOf(bytes.size, 2000), Charsets.UTF_8)
        return when {
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> ScoreType.PDF
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MThd" -> ScoreType.MIDI
            bytes.size > 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "RMID" -> ScoreType.MIDI
            startsWith(0xFF, 0xD8, 0xFF) || startsWith(0x89, 0x50, 0x4E, 0x47) || head.startsWith("RIFF") && head.contains("WEBP") -> ScoreType.IMAGE
            MusicXmlImporter.isZip(bytes) -> zipType()
            MuseScoreImporter.looksLikeMscx(head) -> ScoreType.MUSESCORE
            MeiImporter.looksLikeMei(head) || head.contains("<mei ") || head.contains("<mei>") -> ScoreType.MEI
            head.contains("<score-partwise") || head.contains("<score-timewise") -> ScoreType.MUSICXML
            AbcImporter.looksLikeAbc(String(bytes, 0, minOf(bytes.size, 20000), Charsets.UTF_8)) -> ScoreType.ABC
            head.contains("<?xml") -> ScoreType.MUSICXML
            else -> ScoreType.UNKNOWN
        }
    }

    private fun zipType(): ScoreType {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == SongPackage.MANIFEST -> return ScoreType.PACKAGE
                    e.name.endsWith(".mscx") -> return ScoreType.MUSESCORE
                    e.name == "META-INF/container.xml" -> {}
                }
            }
        }
        return ScoreType.MUSICXML
    }

    /** Name for the original file inside the song package (`original.<ext>`). */
    val originalName: String
        get() {
            val ext = extension.filter { it.isLetterOrDigit() }.take(8).ifEmpty {
                when (type) {
                    ScoreType.PDF -> "pdf"; ScoreType.MIDI -> "mid"; ScoreType.MUSICXML -> "musicxml"; ScoreType.ABC -> "abc"
                    ScoreType.MUSESCORE -> "mscz"; ScoreType.MEI -> "mei"; ScoreType.IMAGE -> "jpg"; else -> "bin"
                }
            }
            return "original.$ext"
        }
}

/** Rendered pages: full-resolution grayscale for recognition, small bitmaps for the preview. */
class RenderedPdf(val pages: List<GrayImage>, val previews: List<Bitmap>, val scale: Float)

object ScoreImporter {
    /** Target page width for recognition: gives ~16–20 px per staff space for typical A4 scores. */
    const val RENDER_WIDTH = 2000
    const val PREVIEW_WIDTH = 1000
    const val MAX_PAGES = 30
    /** Photos are decoded with at most this many pixels on the longer side. */
    const val PHOTO_MAX_SIDE = 4000

    const val SUPPORTED = "PDF, MIDI (.mid/.kar), MusicXML (.xml/.musicxml/.mxl), ABC (.abc), MuseScore (.mscz/.mscx), MEI (.mei), PianoTiles (.pianotiles), Fotos (.jpg/.png)"

    suspend fun read(context: Context, uri: Uri): LoadedFile = withContext(Dispatchers.IO) {
        var name = tr("Neues Stück")
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportException(tr("Datei konnte nicht geöffnet werden"))
        LoadedFile(name, bytes)
    }

    suspend fun importDirect(file: LoadedFile): Song = withContext(Dispatchers.Default) {
        when (file.type) {
            ScoreType.MIDI -> MidiImporter.import(midiBytes(file.bytes), file.baseName)
            ScoreType.MUSICXML -> MusicXmlImporter.import(file.bytes, file.baseName)
            ScoreType.ABC -> AbcImporter.import(file.bytes, file.baseName)
            ScoreType.MUSESCORE -> MuseScoreImporter.import(file.bytes, file.baseName)
            ScoreType.MEI -> MeiImporter.import(file.bytes, file.baseName)
            else -> throw ImportException(tr("Unbekanntes Dateiformat. Unterstützt: %s", SUPPORTED))
        }
    }

    /** RIFF-MIDI (.rmi) contains a normal MIDI file in its data chunk. */
    private fun midiBytes(b: ByteArray): ByteArray {
        if (String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF") return b
        for (i in 12 until b.size - 4) if (b[i] == 'M'.code.toByte() && String(b, i, 4, Charsets.ISO_8859_1) == "MThd") return b.copyOfRange(i, b.size)
        return b
    }

    suspend fun renderPdf(context: Context, bytes: ByteArray, progress: (Int, Int) -> Unit): RenderedPdf = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import.pdf")
        tmp.writeBytes(bytes)
        val pages = ArrayList<GrayImage>()
        val previews = ArrayList<Bitmap>()
        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PAGES)
                if (count == 0) throw ImportException(tr("Das PDF enthält keine Seiten"))
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

    /** Decodes a photo (respecting the EXIF orientation) as grayscale, at most [PHOTO_MAX_SIDE] px. */
    fun decodePhoto(bytes: ByteArray): GrayImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw ImportException(tr("Bild konnte nicht gelesen werden"))
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ImportException(tr("Bild konnte nicht gelesen werden"))
        val rotation = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val gray = GrayImage.fromArgb(bmp.width, bmp.height, px)
        bmp.recycle()
        return gray
    }

    /** Photos → prepared pages (see [PhotoPrep]) + previews + JPEGs of the prepared pages (`scan-NN.jpg`). */
    class PreparedPhotos(val rendered: RenderedPdf, val scans: List<ByteArray>)

    suspend fun preparePhotos(photos: List<ByteArray>, progress: (Int, Int) -> Unit): PreparedPhotos = withContext(Dispatchers.Default) {
        val pages = ArrayList<GrayImage>()
        val previews = ArrayList<Bitmap>()
        val scans = ArrayList<ByteArray>()
        for ((i, bytes) in photos.withIndex()) {
            progress(i, photos.size)
            val prepared = PhotoPrep.prepare(decodePhoto(bytes)).image
            pages.add(prepared)
            val bmp = grayToBitmap(prepared)
            scans.add(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray())
            previews.add(Bitmap.createScaledBitmap(bmp, PREVIEW_WIDTH, (prepared.height.toLong() * PREVIEW_WIDTH / prepared.width).toInt().coerceAtLeast(1), true))
            bmp.recycle()
        }
        // previews are scaled per page; the overlay uses the page width, so one common factor is not needed
        PreparedPhotos(RenderedPdf(pages, previews, PREVIEW_WIDTH.toFloat() / (pages.firstOrNull()?.width ?: RENDER_WIDTH)), scans)
    }

    fun grayToBitmap(img: GrayImage): Bitmap {
        val px = IntArray(img.width * img.height) { val v = img.data[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
    }
}
