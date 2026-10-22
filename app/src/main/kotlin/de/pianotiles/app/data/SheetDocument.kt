package de.pianotiles.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Pages of sheet music – either one PDF or a list of page images (photos). Thread-safe rendering. */
interface SheetDocument : Closeable {
    val pageCount: Int
    /** Renders [page] with the given pixel width on white. */
    fun render(page: Int, width: Int): Bitmap

    companion object {
        /** Opens [files] (one PDF, or JPEG/PNG pages); null if nothing can be opened. */
        fun open(files: List<File>): SheetDocument? = runCatching {
            when {
                files.isEmpty() -> null
                files.size == 1 && files[0].extension.equals("pdf", true) -> PdfSheet(files[0])
                else -> ImageSheet(files.filter { it.exists() }).takeIf { it.pageCount > 0 }
            }
        }.getOrNull()
    }
}

private class PdfSheet(file: File) : SheetDocument {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    override val pageCount: Int get() = renderer.pageCount
    override fun render(page: Int, width: Int): Bitmap = synchronized(this) {
        renderer.openPage(page).use { p ->
            val b = Bitmap.createBitmap(width, (width.toDouble() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        }
    }
    override fun close() = synchronized(this) { runCatching { renderer.close() }; runCatching { fd.close() }; Unit }
}

private class ImageSheet(private val files: List<File>) : SheetDocument {
    override val pageCount: Int get() = files.size
    override fun render(page: Int, width: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[page].path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val src = BitmapFactory.decodeFile(files[page].path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return Bitmap.createBitmap(width, width * 4 / 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        if (src.width == width) return src
        return Bitmap.createScaledBitmap(src, width, (width.toDouble() * src.height / src.width).toInt().coerceAtLeast(1), true)
            .also { if (it !== src) src.recycle() }
    }
    override fun close() {}
}
