package de.pianotiles.app.ui.editor

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.practice.SheetPanel
import de.pianotiles.app.ui.practice.drawLabel
import de.pianotiles.app.ui.practice.handColor
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.edit.MeasureOps
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.SongUtils
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Editor 2.0: the original PDF above (current measure highlighted), the piano roll below.
 * Measure navigation with a "Bitte prüfen" list, measure tools, undo/redo, playback from the measure and
 * "KI-nachbessern" (AI review with proposals that can be accepted one by one).
 */
@Composable
fun EditorScreen(songId: String, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val vm: EditorViewModel = viewModel(key = "editor-$songId") { EditorViewModel(app, songId) }
    if (vm.original == null) { LaunchedEffect(Unit) { onBack() }; return }
    val naming = app.settings.settings.value.noteNaming

    var grid by remember { mutableDoubleStateOf(0.5) }
    var addMode by remember { mutableStateOf(false) }
    var addHand by remember { mutableStateOf(Hand.RIGHT) }
    var pxPerBeat by remember { mutableFloatStateOf(90f) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }
    var askKey by remember { mutableStateOf(false) }
    val song = vm.song

    fun startAi() {
        when {
            !vm.hasApiKey -> askKey = true
            !vm.aiConsent -> askConsent = true
            else -> { showAi = true; vm.startAi() }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ---- top bar
        Surface(color = MaterialTheme.colorScheme.surface) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // narrow (portrait phone): short title, icons instead of labelled buttons, zoom in the measure bar
                val narrow = maxWidth < 640.dp
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (vm.dirty) confirmLeave = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
                    Text(if (narrow) song.title else tr("Noten bearbeiten – %s", song.title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, tr("Rückgängig")) }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, tr("Wiederholen")) }
                    if (vm.pdf != null) {
                        val onAi = { if (showAi && !vm.aiRunning && vm.proposals.isNotEmpty()) showAi = false else if (vm.aiRunning || vm.proposals.isNotEmpty()) showAi = !showAi else startAi() }
                        if (narrow) IconButton(onClick = onAi) { Icon(Icons.Filled.AutoAwesome, tr("KI-nachbessern")) }
                        else {
                            FilledTonalButton(onClick = onAi) {
                                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (vm.aiRunning) tr("KI prüft …") else tr("KI-nachbessern"))
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    if (narrow) IconButton(onClick = { vm.save() }, enabled = vm.dirty) { Icon(Icons.Filled.Save, tr("Speichern")) }
                    else Button(onClick = { vm.save() }, enabled = vm.dirty) { Text(tr("Speichern")) }
                }
            }
        }

        // ---- measure bar
        Surface(color = PtColors.SurfaceHigh) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { vm.measure = (vm.measure - 1).coerceAtLeast(0) }) { Icon(Icons.Filled.ChevronLeft, tr("Vorheriger Takt")) }
                Text(tr("Takt %s / %s", vm.measure + 1, song.measureCount), Modifier.width(130.dp), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { vm.measure = (vm.measure + 1).coerceAtMost(song.measureCount - 1) }) { Icon(Icons.Filled.ChevronRight, tr("Nächster Takt")) }
                IconButton(onClick = { pxPerBeat = (pxPerBeat / 1.3f).coerceAtLeast(20f) }) { Icon(Icons.Filled.ZoomOut, tr("Verkleinern")) }
                IconButton(onClick = { pxPerBeat = (pxPerBeat * 1.3f).coerceAtMost(400f) }) { Icon(Icons.Filled.ZoomIn, tr("Vergrößern")) }
                Box {
                    FilterChip(
                        selected = vm.issues.isNotEmpty(), onClick = { showIssues = true },
                        label = { Text(if (vm.issues.isEmpty()) tr("Alles plausibel") else tr("Bitte prüfen (%s)", vm.issues.size)) },
                        leadingIcon = { Icon(Icons.Filled.Warning, null, tint = if (vm.issues.isEmpty()) PtColors.Hit else Color(0xFFFF9800)) },
                    )
                    DropdownMenu(expanded = showIssues, onDismissRequest = { showIssues = false }) {
                        if (vm.issues.isEmpty()) DropdownMenuItem(text = { Text(tr("Keine auffälligen Takte gefunden")) }, onClick = { showIssues = false })
                        for (issue in vm.issues) DropdownMenuItem(
                            text = { Text(tr("Takt %s: %s", issue.measure + 1, issue.reasons.joinToString("; "))) },
                            onClick = { vm.measure = issue.measure; showIssues = false },
                        )
                    }
                }
                TextButton(onClick = {
                    val next = vm.issues.firstOrNull { it.measure > vm.measure } ?: vm.issues.firstOrNull()
                    next?.let { vm.measure = it.measure }
                }, enabled = vm.issues.isNotEmpty()) { Text(tr("Nächster prüfen ›")) }
                IconButton(onClick = vm::togglePlay) {
                    Icon(if (vm.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (vm.playing) tr("Stopp") else tr("Ab Takt anhören"))
                }
                Box {
                    TextButton(onClick = { showTools = true }) { Icon(Icons.Filled.Build, null); Spacer(Modifier.width(4.dp)); Text(tr("Takt-Werkzeuge")) }
                    DropdownMenu(expanded = showTools, onDismissRequest = { showTools = false }) {
                        val m = vm.measure
                        MenuItem(tr("Takt %s kopieren", m + 1)) { showTools = false; vm.copyMeasure() }
                        MenuItem(tr("In Takt %s einfügen (ersetzen)", m + 1), vm.clipboard != null) { showTools = false; vm.pasteMeasure() }
                        MenuItem(tr("Leeren Takt davor einfügen")) { showTools = false; vm.edit { MeasureOps.insertBefore(it, m) } }
                        MenuItem(tr("Takt %s löschen", m + 1)) { showTools = false; vm.edit { MeasureOps.delete(it, m) } }
                        MenuItem(tr("Takt %s leeren", m + 1)) { showTools = false; vm.edit { MeasureOps.replace(it, m, emptyList()) } }
                        MenuItem(tr("Hände tauschen in Takt %s", m + 1)) { showTools = false; vm.edit { MeasureOps.swapHands(it, m) } }
                        MenuItem(tr("Takt %s ½ Ton höher", m + 1)) { showTools = false; vm.edit { MeasureOps.transpose(it, m, 1) } }
                        MenuItem(tr("Takt %s ½ Ton tiefer", m + 1)) { showTools = false; vm.edit { MeasureOps.transpose(it, m, -1) } }
                        HorizontalDivider()
                        MenuItem(tr("Taktart: %s/%s → ändern", song.beatsPerMeasure, song.beatUnit)) {
                            showTools = false
                            vm.edit { s ->
                                val opts = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8, 3 to 8, 2 to 2, 5 to 4, 9 to 8, 12 to 8)
                                val i = opts.indexOf(s.beatsPerMeasure to s.beatUnit)
                                val (n, d) = opts[(i + 1) % opts.size]
                                s.copy(beatsPerMeasure = n, beatUnit = d)
                            }
                        }
                        MenuItem(tr("Ganzes Stück ½ Ton höher")) { showTools = false; vm.edit { s -> s.copy(notes = s.notes.map { it.copy(pitch = (it.pitch + 1).coerceAtMost(108)) }) } }
                        MenuItem(tr("Ganzes Stück ½ Ton tiefer")) { showTools = false; vm.edit { s -> s.copy(notes = s.notes.map { it.copy(pitch = (it.pitch - 1).coerceAtLeast(21)) }) } }
                        MenuItem(tr("Alles am Raster ausrichten")) { showTools = false; vm.edit { s -> s.copy(notes = SongUtils.quantize(s.notes, grid)) } }
                    }
                }
                Text(tr("Raster:"), color = PtColors.TextDim)
                for ((g, label) in listOf(0.25 to "1/16", 0.5 to "1/8", 1.0 to "1/4", 1.0 / 3 to tr("Triole"))) {
                    FilterChip(selected = grid == g, onClick = { grid = g }, label = { Text(label) })
                }
                FilterChip(selected = addMode, onClick = { addMode = !addMode }, label = { Text(tr("Hinzufügen")) }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                if (addMode) FilterChip(selected = true, onClick = { addHand = if (addHand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT },
                    label = { Text(if (addHand == Hand.RIGHT) tr("rechte Hand") else tr("linke Hand")) })
            }
        }

        // ---- sheet + piano roll (+ AI panel)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val narrowArea = maxWidth < 640.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                vm.pdf?.let { pdf ->
                    SheetPanel(
                        pages = pdf, measures = song.sheetMeasures, currentMeasure = vm.measure, loopMeasures = null,
                        onSeekMeasure = { vm.measure = it }, modifier = Modifier.fillMaxWidth().weight(0.42f),
                        marked = vm.issues.mapTo(HashSet()) { it.measure },
                    )
                }
                PianoRoll(vm, grid, addMode, addHand, pxPerBeat, Modifier.fillMaxWidth().weight(0.58f))
            }
            if (showAi && !narrowArea) AiPanel(vm, naming, onClose = { showAi = false }, onRestart = ::startAi, modifier = Modifier.width(360.dp).fillMaxHeight())
        }
        if (showAi && narrowArea) AiPanel(vm, naming, onClose = { showAi = false }, onRestart = ::startAi, modifier = Modifier.fillMaxSize())
        }

        SelectionBar(vm, grid, naming)
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(tr("Änderungen speichern?")) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; vm.save(onBack) }) { Text(tr("Speichern")) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text(tr("Verwerfen")) } },
    )
    if (askKey) AlertDialog(
        onDismissRequest = { askKey = false },
        icon = { Icon(Icons.Filled.AutoAwesome, null) },
        title = { Text(tr("KI-nachbessern einrichten")) },
        text = {
            Text(
                tr("Die KI vergleicht jede Seite des Notenblatts mit den erkannten Noten und schlägt Korrekturen vor. Dafür brauchst du einen eigenen API-Schlüssel von Anthropic (console.anthropic.com). Trage ihn in den Einstellungen unter „KI-nachbessern“ ein – er bleibt verschlüsselt auf diesem Gerät."),
            )
        },
        confirmButton = { TextButton(onClick = { askKey = false; onOpenSettings() }) { Text(tr("Zu den Einstellungen")) } },
        dismissButton = { TextButton(onClick = { askKey = false }) { Text(tr("Abbrechen")) } },
    )
    if (askConsent) AlertDialog(
        onDismissRequest = { askConsent = false },
        title = { Text(tr("Daten an die KI senden?")) },
        text = {
            Text(
                tr("Für jede der %s Seite(n) werden ein Bild der Notenseite und die erkannten Noten an Anthropic (Claude) geschickt. Die Kosten werden über deinen API-Schlüssel abgerechnet (ein Aufruf pro Seite). Es werden keine anderen Daten übertragen. Die Vorschläge werden erst übernommen, wenn du sie bestätigst.", vm.aiPages.size),
            )
        },
        confirmButton = { TextButton(onClick = { askConsent = false; vm.aiConsent = true; showAi = true; vm.startAi() }) { Text(tr("Verstanden, senden")) } },
        dismissButton = { TextButton(onClick = { askConsent = false }) { Text(tr("Abbrechen")) } },
    )
}

@Composable
private fun PianoRoll(vm: EditorViewModel, grid: Double, addMode: Boolean, addHand: Hand, pxPerBeat: Float, modifier: Modifier) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val song = vm.song
    val lo = max(Pitch.LOWEST_PIANO, (song.notes.minOfOrNull { it.pitch } ?: 60) - 4)
    val hi = minOf(Pitch.HIGHEST_PIANO, (song.notes.maxOfOrNull { it.pitch } ?: 72) + 4)
    val measurer = rememberTextMeasurer(cacheSize = 512)
    var scrollBeats by remember { mutableDoubleStateOf(song.measureStart(vm.measure) - 0.5) }
    // The measure the view was scrolled to by hand (so following vm.measure does not fight the user).
    var scrolledMeasure by remember { mutableIntStateOf(vm.measure) }
    LaunchedEffect(vm.measure) {
        if (vm.measure != scrolledMeasure) { scrollBeats = song.measureStart(vm.measure) - 0.5; scrolledMeasure = vm.measure }
    }
    val labelW = 56f
    val issueSet = vm.issues.mapTo(HashSet()) { it.measure }
    val preview = vm.previewProposal

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(lo, hi, addMode, grid, addHand, pxPerBeat) {
                    fun rowH() = size.height.toFloat() / (hi - lo + 1)
                    detectTapGestures { o ->
                        if (o.x < labelW) return@detectTapGestures
                        val beat = scrollBeats + (o.x - labelW) / pxPerBeat
                        val pitch = hi - (o.y / rowH()).toInt()
                        val s = vm.song
                        val hit = s.notes.indexOfFirst { it.pitch == pitch && beat >= it.start && beat <= it.end }
                        val m = s.measureIndexAt(max(0.0, beat))
                        scrolledMeasure = m; vm.measure = m
                        if (hit >= 0) { vm.selected = hit; vm.preview(s.notes[hit].pitch) }
                        else if (addMode) vm.addNote(Note(pitch, floor(beat / grid + 1e-6) * grid, grid, addHand))
                        else vm.selected = -1
                    }
                }
                .pointerInput(lo, hi, grid, pxPerBeat) {
                    fun rowH() = size.height.toFloat() / (hi - lo + 1)
                    var mode = 0 // 0 scroll, 1 move note, 2 resize note
                    var index = -1
                    var orig: Note? = null
                    var dx = 0f; var dy = 0f
                    detectDragGestures(
                        onDragStart = { o ->
                            val beat = scrollBeats + (o.x - labelW) / pxPerBeat
                            val pitch = hi - (o.y / rowH()).toInt()
                            val s = vm.song
                            val hit = if (o.x < labelW) -1 else s.notes.indexOfFirst { it.pitch == pitch && beat >= it.start - 0.05 && beat <= it.end + 0.05 }
                            dx = 0f; dy = 0f
                            if (hit >= 0) {
                                index = hit; orig = s.notes[hit]; vm.selected = hit
                                val endX = labelW + ((s.notes[hit].end - scrollBeats) * pxPerBeat).toFloat()
                                mode = if (abs(o.x - endX) < 28f) 2 else 1
                                vm.beginGesture()
                            } else mode = 0
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            if (mode == 0) {
                                scrollBeats = (scrollBeats - drag.x / pxPerBeat).coerceIn(-1.0, max(0.0, vm.song.lengthBeats))
                                val m = vm.song.measureIndexAt(max(0.0, scrollBeats + (size.width - labelW) / pxPerBeat * 0.3))
                                if (m != vm.measure) { scrolledMeasure = m; vm.measure = m }
                            } else {
                                dx += drag.x; dy += drag.y
                                val o = orig ?: return@detectDragGestures
                                val n = if (mode == 1) {
                                    val start = (((o.start + dx / pxPerBeat) / grid).roundToInt() * grid).coerceAtLeast(0.0)
                                    val pitch = (o.pitch - (dy / rowH()).roundToInt()).coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)
                                    o.copy(start = start, pitch = pitch)
                                } else {
                                    o.copy(duration = (((o.duration + dx / pxPerBeat) / grid).roundToInt() * grid).coerceAtLeast(grid))
                                }
                                val cur = vm.song.notes.getOrNull(index)
                                if (cur != null && cur != n) {
                                    if (cur.pitch != n.pitch) vm.preview(n.pitch)
                                    vm.gestureUpdate { s -> s.copy(notes = s.notes.toMutableList().also { it[index] = n }) }
                                    vm.selected = index
                                }
                            }
                        },
                    )
                },
        ) {
            val rows = hi - lo + 1
            val rowH = size.height / rows
            for (p in lo..hi) {
                val y = (hi - p) * rowH
                drawRect(if (Pitch.isBlackKey(p)) Color(0xFF12172A) else Color(0xFF181E36), Offset(labelW, y), Size(size.width - labelW, rowH))
                drawRect(if (Pitch.isBlackKey(p)) PtColors.BlackKey else PtColors.WhiteKey, Offset(0f, y), Size(labelW - 4, rowH - 1))
                if (p % 12 == 0 && rowH > 9) drawLabel(measurer, Pitch.fullName(p, app.settings.settings.value.noteNaming), Offset(4f, y + rowH / 2 - 9f), Color(0xFF333A55), 10)
            }
            fun xOf(beat: Double) = labelW + ((beat - scrollBeats) * pxPerBeat).toFloat()
            // current / suspicious measures
            run {
                val x0 = max(labelW, xOf(song.measureStart(vm.measure)))
                val x1 = xOf(song.measureEnd(vm.measure))
                if (x1 > x0) drawRect(Color(0x1A4FC3F7), Offset(x0, 0f), Size(x1 - x0, size.height))
            }
            val firstBeat = floor(scrollBeats).toInt()
            val lastBeat = (scrollBeats + (size.width - labelW) / pxPerBeat).toInt() + 1
            for (b in firstBeat..lastBeat) {
                val x = xOf(b.toDouble())
                if (x >= labelW) drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            var m = max(0, song.measureIndexAt(max(0.0, scrollBeats)))
            while (m <= song.measureCount + 64) {
                val x = xOf(song.measureStart(m))
                if (x > size.width) break
                if (m in issueSet) {
                    val xe = xOf(song.measureEnd(m))
                    drawRect(Color(0x55FF9800), Offset(max(labelW, x), 0f), Size(max(0f, xe - max(labelW, x)), 6f))
                }
                if (x >= labelW) {
                    drawLine(Color(0x88B388FF), Offset(x, 0f), Offset(x, size.height), 2f)
                    drawLabel(measurer, "${m + 1}", Offset(x + 4f, 8f), Color(0xAAB388FF), 11)
                }
                m++
            }
            for ((i, n) in song.notes.withIndex()) {
                val x = xOf(n.start)
                val w = (n.duration * pxPerBeat).toFloat()
                if (x + w < labelW || x > size.width) continue
                val y = (hi - n.pitch) * rowH
                val faded = preview != null && n.hand == preview.correction.hand && song.measureIndexAt(n.start) == preview.correction.measure
                val xs = max(labelW, x)
                drawRoundRect(handColor(n.hand).copy(alpha = if (faded) 0.3f else 1f), Offset(xs, y + 1), Size(max(2f, w - 2 - (xs - x)), rowH - 2), CornerRadius(4f))
                if (i == vm.selected) {
                    drawRoundRect(Color.White, Offset(x - 2, y - 1), Size(w + 2, rowH + 2), CornerRadius(4f), style = Stroke(3f))
                    drawRect(Color.White, Offset(x + w - 6, y + rowH * 0.2f), Size(4f, rowH * 0.6f)) // resize handle
                }
            }
            // AI proposal preview: dashed green outlines
            preview?.let { p ->
                val ms = song.measureStart(p.correction.measure)
                for (n in p.correction.notes) {
                    if (n.pitch !in lo..hi) continue
                    val x = xOf(ms + n.start)
                    val y = (hi - n.pitch) * rowH
                    drawRoundRect(
                        PtColors.Hit, Offset(x, y + 1), Size((n.duration * pxPerBeat).toFloat() - 2, rowH - 2), CornerRadius(4f),
                        style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                    )
                }
            }
        }
        if (song.notes.isEmpty()) Text(tr("Noch keine Noten – „Hinzufügen“ wählen und auf das Raster tippen."), Modifier.align(Alignment.Center), color = PtColors.TextDim)
    }
}

@Composable
private fun SelectionBar(vm: EditorViewModel, grid: Double, naming: de.pianotiles.core.model.NoteNaming) {
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val song = vm.song
            val sel = song.notes.getOrNull(vm.selected)
            if (sel == null) {
                Text(
                    tr("Note antippen = auswählen · Note ziehen = verschieben (am rechten Rand: Länge) · freie Fläche ziehen = blättern"),
                    color = PtColors.TextDim,
                )
            } else {
                Text(tr("%s · Takt %s · %s", Pitch.fullName(sel.pitch, naming), song.measureIndexAt(sel.start) + 1, durationName(sel.duration)), Modifier.width(200.dp))
                TextButton(onClick = { vm.modifySelected { it.copy(pitch = (it.pitch + 1).coerceAtMost(108)) } }) { Text("♯", style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { vm.modifySelected { it.copy(pitch = (it.pitch - 1).coerceAtLeast(21)) } }) { Text("♭", style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = { vm.modifySelected { it.copy(pitch = (it.pitch + 12).coerceAtMost(108)) } }) { Icon(Icons.Filled.KeyboardDoubleArrowUp, tr("Oktave höher")) }
                IconButton(onClick = { vm.modifySelected { it.copy(pitch = (it.pitch - 12).coerceAtLeast(21)) } }) { Icon(Icons.Filled.KeyboardDoubleArrowDown, tr("Oktave tiefer")) }
                IconButton(onClick = { vm.modifySelected { it.copy(start = max(0.0, it.start - grid)) } }) { Icon(Icons.Filled.ChevronLeft, tr("früher")) }
                IconButton(onClick = { vm.modifySelected { it.copy(start = it.start + grid) } }) { Icon(Icons.Filled.ChevronRight, tr("später")) }
                IconButton(onClick = { vm.modifySelected { it.copy(duration = max(grid / 2, it.duration - grid)) } }) { Icon(Icons.Filled.Remove, tr("kürzer")) }
                IconButton(onClick = { vm.modifySelected { it.copy(duration = it.duration + grid) } }) { Icon(Icons.Filled.Add, tr("länger")) }
                for ((d, label) in listOf(0.25 to "1/16", 0.5 to "1/8", 1.0 to "1/4", 1.5 to "1/4.", 2.0 to "1/2", 4.0 to "1/1")) {
                    TextButton(onClick = { vm.modifySelected { it.copy(duration = d) } }) { Text(label) }
                }
                FilterChip(selected = true, onClick = { vm.modifySelected { it.copy(hand = if (it.hand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT) } },
                    label = { Text(if (sel.hand == Hand.RIGHT) tr("Rechte Hand") else tr("Linke Hand")) })
                // fingering: tap cycles 1 → 5
                FilterChip(selected = sel.finger in 1..5, onClick = { vm.setFinger(sel.finger % 5 + 1) },
                    label = { Text(if (sel.finger in 1..5) tr("Finger %s", sel.finger) else tr("Finger –")) })
                IconButton(onClick = {
                    scope.launch { song.notes.filter { abs(it.start - sel.start) < 0.02 }.forEach { vm.preview(it.pitch) } }
                }) { Icon(Icons.Filled.PlayArrow, tr("Anhören")) }
                IconButton(onClick = vm::deleteSelected) { Icon(Icons.Filled.Delete, tr("Löschen"), tint = PtColors.Miss) }
            }
        }
    }
}

internal fun durationName(beats: Double): String = when ((beats * 100).roundToInt()) {
    in 23..25 -> tr("Sechzehntel"); in 31..34 -> tr("Triolen-Achtel"); in 45..50 -> tr("Achtel"); in 70..75 -> tr("punkt. Achtel")
    in 95..100 -> tr("Viertel"); in 140..150 -> tr("punkt. Viertel"); in 190..200 -> tr("Halbe"); in 285..300 -> tr("punkt. Halbe"); in 380..400 -> tr("Ganze")
    else -> tr("%.2f Schläge", beats)
}

internal fun durationSymbol(beats: Double): String = when ((beats * 100).roundToInt()) {
    in 23..25 -> "1/16"; in 31..34 -> tr("1/8 (Triole)"); in 45..50 -> "1/8"; in 70..75 -> "1/8."; in 95..100 -> "1/4"; in 140..150 -> "1/4."
    in 190..200 -> "1/2"; in 285..300 -> "1/2."; in 380..400 -> "1/1"
    else -> "%.2f".format(beats)
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = onClick)
