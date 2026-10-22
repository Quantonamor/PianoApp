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
import de.pianotiles.core.model.Song
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

class ImportViewModel(app: Application, private val uri: Uri) : AndroidViewModel(app) {
    private val pt = app as PianoTilesApp
    var state by mutableStateOf<ImportState>(ImportState.Working(tr("Datei wird gelesen …"), null))
        private set

    private var file: LoadedFile? = null
    private var pages: List<GrayImage> = emptyList()
    private var previews: List<Bitmap> = emptyList()
    private var job: Job? = null

    init { start() }

    private fun start() {
        job = viewModelScope.launch {
            try {
                val f = ScoreImporter.read(getApplication(), uri)
                file = f
                when (f.type) {
                    ScoreType.PDF -> {
                        val rendered = ScoreImporter.renderPdf(getApplication(), f.bytes) { i, n ->
                            state = ImportState.Working(tr("Seite %s von %s wird gerendert …", i + 1, n), i.toFloat() / n)
                        }
                        pages = rendered.pages
                        previews = rendered.previews
                        recognize(OmrOptions(title = f.baseName))
                    }
                    else -> {
                        state = ImportState.Working(tr("Noten werden eingelesen …"), null)
                        val song = ScoreImporter.importDirect(f)
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
            val song: Song = review.result.song.copy(title = title.trim().ifEmpty { tr("PDF-Import") }, bpm = bpm)
            file?.let { pt.songs.storePdf(song.id, it.bytes) }
            pt.songs.save(song)
            state = ImportState.Done(song.id, edit)
        }
    }
}
