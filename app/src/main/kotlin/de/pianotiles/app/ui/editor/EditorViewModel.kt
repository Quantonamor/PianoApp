package de.pianotiles.app.ui.editor

import android.app.Application
import android.graphics.Bitmap
import de.pianotiles.app.data.SheetDocument
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.i18n.I18n
import de.pianotiles.app.i18n.tr
import de.pianotiles.core.ai.AiCorrection
import de.pianotiles.core.ai.AiEvent
import de.pianotiles.core.ai.AiFixRunner
import de.pianotiles.core.ai.ClaudeReviewer
import de.pianotiles.core.ai.PageImage
import de.pianotiles.core.edit.MeasureIssue
import de.pianotiles.core.edit.MeasureOps
import de.pianotiles.core.edit.RelNote
import de.pianotiles.core.model.FingeringSource
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongUtils
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A measure/hand correction suggested by the AI, waiting for the user's decision. */
data class Proposal(
    val id: Int,
    val correction: AiCorrection,
    val before: List<RelNote>,
    val status: Status = Status.OPEN,
) {
    enum class Status { OPEN, ACCEPTED, REJECTED }
}

/** Editor state: the song being edited with undo/redo, measure navigation and the AI review. */
class EditorViewModel(app: Application, songId: String) : AndroidViewModel(app) {
    private val pt = app as PianoTilesApp
    val original: Song? = pt.songs.get(songId)

    var song by mutableStateOf(original ?: Song(id = songId, title = "", notes = emptyList()))
        private set
    var dirty by mutableStateOf(false)
        private set
    var selected by mutableIntStateOf(-1)
    var measure by mutableIntStateOf(0)
    var issues by mutableStateOf(MeasureOps.issues(song))
        private set
    private val undoStack = ArrayDeque<Song>()
    private val redoStack = ArrayDeque<Song>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var clipboard by mutableStateOf<List<RelNote>?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    private var playJob: Job? = null

    /** Original sheet pages (PDF or prepared photos), if the measure positions are known. */
    val pdf = pt.songs.sheetPages(songId).takeIf { it.isNotEmpty() && song.sheetMeasures.isNotEmpty() }

    private fun afterChange() {
        dirty = true
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        issues = MeasureOps.issues(song)
        measure = measure.coerceIn(0, song.measureCount - 1)
        if (selected !in song.notes.indices) selected = -1
    }

    /** Applies [transform] as one undoable step. */
    fun edit(keepSelection: Boolean = false, transform: (Song) -> Song) {
        val next = transform(song)
        if (next == song) return
        undoStack.addLast(song); if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        song = next
        if (!keepSelection) selected = -1
        afterChange()
    }

    /** Starts a drag: one undo step for the whole gesture. */
    fun beginGesture() {
        undoStack.addLast(song); if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Changes during a drag gesture (no extra undo steps). */
    fun gestureUpdate(transform: (Song) -> Song) { song = transform(song); afterChange() }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(song); song = prev; selected = -1; afterChange()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(song); song = next; selected = -1; afterChange()
    }

    fun modifySelected(transform: (Note) -> Note) {
        val i = selected
        if (i !in song.notes.indices) return
        edit(keepSelection = true) { s -> s.copy(notes = s.notes.toMutableList().also { it[i] = transform(it[i]) }) }
        pt.synth.noteOn(song.notes[i].pitch, 80, 0.4)
    }

    /** Sets the finger of the selected note by hand (1–5); the song's fingering then counts as edited by hand. */
    fun setFinger(finger: Int) {
        val i = selected
        if (i !in song.notes.indices) return
        edit(keepSelection = true) { s ->
            s.copy(notes = s.notes.toMutableList().also { it[i] = it[i].copy(finger = finger) }, fingeringSource = FingeringSource.MANUAL)
        }
    }

    fun addNote(n: Note) {
        edit { it.copy(notes = it.notes + n) }
        selected = song.notes.lastIndex
        pt.synth.noteOn(n.pitch, 80, 0.4)
    }

    fun deleteSelected() {
        val i = selected
        if (i in song.notes.indices) edit { s -> s.copy(notes = s.notes.filterIndexed { j, _ -> j != i }) }
    }

    fun copyMeasure() { clipboard = MeasureOps.copy(song, measure) }
    fun pasteMeasure() { clipboard?.let { c -> edit { MeasureOps.replace(it, measure, c) } } }

    fun save(then: () -> Unit = {}) {
        var s = song.copy(notes = SongUtils.dedupe(song.notes))
        // an automatic fingering is worked out again for the edited notes when practising
        if (s.fingeringSource == FingeringSource.AUTO) s = s.copy(notes = s.notes.map { it.copy(finger = 0) })
        pt.appScope.launch { pt.songs.save(s); dirty = false; then() }
    }

    fun preview(pitch: Int) = pt.synth.noteOn(pitch, 80, 0.4)

    /** Plays two measures from the current one (or stops). */
    fun togglePlay() {
        if (playing) { playJob?.cancel(); pt.synth.allNotesOff(); playing = false; return }
        pt.synth.start()
        val s = song
        val from = s.measureStart(measure)
        val to = s.measureEnd(measure + 1)
        val ns = s.notes.filter { it.start >= from - 1e-6 && it.start < to - 1e-6 }.sortedBy { it.start }
        playing = true
        playJob = viewModelScope.launch {
            var t = from
            for (n in ns) {
                if (n.start > t) {
                    delay(((n.start - t) * 60_000 / s.bpmAt(t)).toLong())
                    t = n.start
                }
                pt.synth.noteOn(n.pitch, n.velocity, n.duration * 60 / s.bpmAt(n.start))
            }
            delay(((to - t) * 60_000 / s.bpmAt(t)).toLong().coerceAtMost(3000))
            playing = false
        }
    }

    // ---------------------------------------------------------------- KI-nachbessern

    var aiRunning by mutableStateOf(false)
        private set
    var aiProgress by mutableStateOf(0f)
        private set
    var aiStatus by mutableStateOf("")
        private set
    val aiLog = mutableStateListOf<String>()
    val proposals = mutableStateListOf<Proposal>()
    var previewProposal by mutableStateOf<Proposal?>(null)
    private var aiJob: Job? = null
    @Volatile private var aiCancelled = false

    val hasApiKey: Boolean get() = !pt.secrets.apiKey.isNullOrBlank()
    var aiConsent: Boolean
        get() = pt.secrets.aiConsent
        set(v) { pt.secrets.aiConsent = v }

    val aiPages: List<Int> get() = song.sheetMeasures.map { it.page }.distinct().sorted()

    fun startAi() {
        val key = pt.secrets.apiKey
        val file = pdf
        if (aiRunning || key.isNullOrBlank() || file == null) return
        aiCancelled = false
        aiRunning = true
        aiProgress = 0f
        aiLog.clear(); proposals.clear(); previewProposal = null
        val snapshot = song
        val model = pt.secrets.aiModel
        aiLog.add(tr("Starte KI-Prüfung mit %s – %s Seite(n).", model, aiPages.size))
        aiJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    (SheetDocument.open(file) ?: error(tr("Notenblatt nicht verfügbar"))).use { renderer ->
                        run {
                            ClaudeReviewer(key, model, language = I18n.aiLanguage).use { reviewer ->
                                AiFixRunner(reviewer).run(
                                    snapshot, aiPages,
                                    loadPage = { page -> renderPage(renderer, page) },
                                    keyName = null, warnings = emptyList(),
                                    isCancelled = { aiCancelled },
                                ) { ev -> viewModelScope.launch { onAiEvent(ev, snapshot) } }
                            }
                        }
                    }
                }
            }
            result.exceptionOrNull()?.let { aiLog.add(tr("Fehler: %s", it.message ?: it.javaClass.simpleName)) }
            aiRunning = false
            aiProgress = 1f
            val open = proposals.count { it.status == Proposal.Status.OPEN }
            aiStatus = if (aiCancelled) tr("Abgebrochen") else if (open == 0) tr("Fertig – keine Änderungen vorgeschlagen") else tr("Fertig – %s Vorschläge", open)
        }
    }

    fun cancelAi() { aiCancelled = true; aiStatus = tr("Wird abgebrochen …") }

    private fun onAiEvent(ev: AiEvent, snapshot: Song) {
        when (ev) {
            is AiEvent.Progress -> { aiProgress = if (ev.total == 0) 1f else ev.done.toFloat() / ev.total; aiStatus = ev.message }
            is AiEvent.Log -> aiLog.add(ev.message)
            is AiEvent.PageResult -> ev.corrections.forEach { c ->
                val before = MeasureOps.copy(snapshot, c.measure).filter { it.hand == c.hand }
                // Skip "corrections" that change nothing.
                fun key(ns: List<RelNote>) = ns.map { Triple(it.pitch, Math.round(it.start * 48), Math.round(it.duration * 4)) }.toSet()
                if (key(before) != key(c.notes)) proposals.add(Proposal(proposals.size, c, before))
            }
        }
    }

    private fun renderPage(r: SheetDocument, page: Int): PageImage {
        val bmp = r.render(page, 1600)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return PageImage(page, out.toByteArray(), jpeg = true)
    }

    fun accept(p: Proposal) {
        val i = proposals.indexOfFirst { it.id == p.id }
        if (i < 0 || proposals[i].status != Proposal.Status.OPEN) return
        edit { MeasureOps.replace(it, p.correction.measure, p.correction.notes, p.correction.hand) }
        proposals[i] = proposals[i].copy(status = Proposal.Status.ACCEPTED)
        if (previewProposal?.id == p.id) previewProposal = null
    }

    fun reject(p: Proposal) {
        val i = proposals.indexOfFirst { it.id == p.id }
        if (i >= 0) proposals[i] = proposals[i].copy(status = Proposal.Status.REJECTED)
        if (previewProposal?.id == p.id) previewProposal = null
    }

    /** Accepts all open proposals as one undo step. */
    fun acceptAll() {
        val open = proposals.filter { it.status == Proposal.Status.OPEN }
        if (open.isEmpty()) return
        edit { s -> open.fold(s) { acc, p -> MeasureOps.replace(acc, p.correction.measure, p.correction.notes, p.correction.hand) } }
        for (p in open) proposals[proposals.indexOfFirst { it.id == p.id }] = p.copy(status = Proposal.Status.ACCEPTED)
        previewProposal = null
    }

    fun handName(h: Hand) = if (h == Hand.RIGHT) tr("rechte Hand") else tr("linke Hand")

    override fun onCleared() {
        aiCancelled = true
        playJob?.cancel()
    }
}
