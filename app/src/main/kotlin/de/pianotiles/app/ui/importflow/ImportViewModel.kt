package de.pianotiles.app.ui.importflow

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.importing.LoadedFile
import de.pianotiles.app.importing.ScoreImporter
import de.pianotiles.app.importing.ScoreType
import de.pianotiles.core.io.ImportException
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.omr.GrayImage
import de.pianotiles.core.omr.OmrEngine
import de.pianotiles.core.omr.OmrOptions
import de.pianotiles.core.omr.OmrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data class Working(val message: String, val progress: Float?) : ImportState
    data class Review(val result: OmrResult, val previews: List<Bitmap>, val options: OmrOptions) : ImportState
    data class Failed(val message: String) : ImportState
    data class Done(val songId: String, val edit: Boolean) : ImportState
}

/**
 * Import of one file (PDF, MIDI, MusicXML, ABC, MuseScore, MEI, package, photo) or of several photos
 * ([uris] with more than one entry, or images). PDFs and photos go through the recognition with a review step.
 */
class ImportViewModel(app: Application, private val uris: List<Uri>) : AndroidViewModel(app) {
    private val pt = app as PianoTilesApp
    var state by mutableStateOf<ImportState>(ImportState.Working(tr("Datei wird gelesen …"), null))
        private set

    private var file: LoadedFile? = null
    /** Photos of a photo import (originals) and the prepared pages the recognition worked on. */
    private var photos: List<LoadedFile> = emptyList()
    private var scans: List<ByteArray> = emptyList()
    private var pages: List<GrayImage> = emptyList()
    private var previews: List<Bitmap> = emptyList()
    private var job: Job? = null

    init { start() }

    private fun start() {
        job = viewModelScope.launch {
            try {
                val files = uris.map { ScoreImporter.read(getApplication(), it) }
                val f = files.first()
                file = f
                when {
                    files.size > 1 || f.type == ScoreType.IMAGE -> {
                        if (files.any { it.type != ScoreType.IMAGE }) throw ImportException(tr("Mehrere Dateien gehen nur bei Fotos"))
                        photos = files
                        val prepared = ScoreImporter.preparePhotos(files.map { it.bytes }) { i, n ->
                            viewModelScope.launch(Dispatchers.Main) { state = ImportState.Working(tr("Foto %s von %s wird aufbereitet …", i + 1, n), i.toFloat() / n) }
                        }
                        pages = prepared.rendered.pages
                        previews = prepared.rendered.previews
                        scans = prepared.scans
                        recognize(OmrOptions(title = if (files.size == 1) f.baseName else tr("Foto-Import")))
                    }
                    f.type == ScoreType.PDF -> {
                        val rendered = ScoreImporter.renderPdf(getApplication(), f.bytes) { i, n ->
                            state = ImportState.Working(tr("Seite %s von %s wird gerendert …", i + 1, n), i.toFloat() / n)
                        }
                        pages = rendered.pages
                        previews = rendered.previews
                        recognize(OmrOptions(title = f.baseName))
                    }
                    f.type == ScoreType.PACKAGE -> {
                        state = ImportState.Working(tr("Paket wird eingelesen …"), null)
                        val song = pt.songs.importPackage(f.bytes)
                        state = ImportState.Done(song.id, edit = false)
                    }
                    else -> {
                        state = ImportState.Working(tr("Noten werden eingelesen …"), null)
                        val song = ScoreImporter.importDirect(f)
                        pt.songs.storeOriginal(song.id, f.originalName, f.bytes)
                        pt.songs.save(song)
                        state = ImportState.Done(song.id, edit = false)
                    }
                }
            } catch (e: Exception) {
                state = ImportState.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                state = ImportState.Failed(tr("Nicht genug Speicher für dieses PDF. Versuche ein PDF mit weniger Seiten."))
            }
        }
    }

    fun recognize(options: OmrOptions) {
        job?.cancel()
        job = viewModelScope.launch {
            state = ImportState.Working(tr("Noten werden erkannt …"), 0f)
            try {
                val result = withContext(Dispatchers.Default) {
                    OmrEngine(options).recognize(pages) { i, n ->
                        val p = if (n == 0) 1f else i.toFloat() / n
                        viewModelScope.launch(Dispatchers.Main) { state = ImportState.Working(tr("Noten werden erkannt … Seite %s von %s", minOf(i + 1, n), n), p) }
                    }
                }
                state = ImportState.Review(result, previews, options)
            } catch (e: Exception) {
                state = ImportState.Failed(tr("Erkennung fehlgeschlagen: %s", e.message))
            }
        }
    }

    fun save(review: ImportState.Review, title: String, bpm: Double, edit: Boolean) {
        viewModelScope.launch {
            val isPhoto = photos.isNotEmpty()
            val song: Song = review.result.song.copy(
                title = title.trim().ifEmpty { if (isPhoto) tr("Foto-Import") else tr("PDF-Import") }, bpm = bpm,
                source = if (isPhoto) SongSource.PHOTO else SongSource.PDF,
            )
            if (isPhoto) {
                photos.forEachIndexed { i, p ->
                    val ext = if (p.extension == "png") "png" else "jpg"
                    pt.songs.storeOriginal(song.id, "original-%02d.%s".format(i + 1, ext), p.bytes)
                }
                scans.forEachIndexed { i, b -> pt.songs.storeOriginal(song.id, "scan-%02d.jpg".format(i + 1), b) }
            } else file?.let { pt.songs.storePdf(song.id, it.bytes) }
            pt.songs.save(song)
            state = ImportState.Done(song.id, edit)
        }
    }
}
