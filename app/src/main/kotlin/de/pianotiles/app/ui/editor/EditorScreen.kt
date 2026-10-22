package de.pianotiles.app.ui.editor

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.ui.practice.drawLabel
import de.pianotiles.app.ui.practice.handColor
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Piano-roll editor to correct recognition errors or write simple pieces.
 * Tap a note to select it; in "Hinzufügen" mode a tap on an empty spot creates a note.
 * Drag to scroll horizontally.
 */
@Composable
fun EditorScreen(songId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val original = remember { app.songs.get(songId) }
    if (original == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }
    val scope = rememberCoroutineScope()
    val notes = remember { mutableStateListOf<Note>().apply { addAll(original.notes.sortedBy { it.start }) } }
    val undo = remember { ArrayList<List<Note>>() }
    var selected by remember { mutableIntStateOf(-1) }
    var grid by remember { mutableDoubleStateOf(0.5) }
    var addMode by remember { mutableStateOf(false) }
    var addHand by remember { mutableStateOf(Hand.RIGHT) }
    var scrollBeats by remember { mutableDoubleStateOf(0.0) }
    var pxPerBeat by remember { mutableFloatStateOf(90f) }
    var dirty by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(original) }

    fun snapshot() { undo.add(notes.toList()); if (undo.size > 100) undo.removeAt(0); dirty = true }
    fun modify(f: (Note) -> Note) {
        if (selected !in notes.indices) return
        snapshot()
        notes[selected] = f(notes[selected])
        app.synth.noteOn(notes[selected].pitch, 80, 0.4)
    }
    fun save() {
        val s: Song = meta.copy(notes = SongUtils.dedupe(notes.toList()))
        scope.launch { app.songs.save(s); dirty = false }
    }

    val lo = max(Pitch.LOWEST_PIANO, (notes.minOfOrNull { it.pitch } ?: 60) - 5)
    val hi = minOf(Pitch.HIGHEST_PIANO, (notes.maxOfOrNull { it.pitch } ?: 72) + 5)
    val measurer = rememberTextMeasurer(cacheSize = 512) // note names repeat: measure each once

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (dirty) confirmLeave = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text("Noten bearbeiten – ${meta.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { if (undo.isNotEmpty()) { notes.clear(); notes.addAll(undo.removeAt(undo.lastIndex)); selected = -1 } }, enabled = undo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Rückgängig")
                }
                IconButton(onClick = { pxPerBeat = (pxPerBeat / 1.3f).coerceAtLeast(20f) }) { Icon(Icons.Filled.ZoomOut, "Verkleinern") }
                IconButton(onClick = { pxPerBeat = (pxPerBeat * 1.3f).coerceAtMost(400f) }) { Icon(Icons.Filled.ZoomIn, "Vergrößern") }
                Text("Raster:", Modifier.padding(horizontal = 4.dp))
                for ((g, label) in listOf(0.25 to "1/16", 0.5 to "1/8", 1.0 to "1/4")) {
                    FilterChip(selected = grid == g, onClick = { grid = g }, label = { Text(label) }, modifier = Modifier.padding(end = 4.dp))
                }
                FilterChip(selected = addMode, onClick = { addMode = !addMode }, label = { Text("Hinzufügen") },
                    leadingIcon = { Icon(Icons.Filled.Add, null) }, modifier = Modifier.padding(end = 4.dp))
                if (addMode) FilterChip(selected = true, onClick = { addHand = if (addHand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT },
                    label = { Text(if (addHand == Hand.RIGHT) "rechte Hand" else "linke Hand") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::save, enabled = dirty) { Text("Speichern") }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(lo, hi, addMode, grid, addHand) {
                        detectTapGestures { o ->
                            val rows = hi - lo + 1
                            val rowH = size.height.toFloat() / rows
                            val labelW = 56f
                            if (o.x < labelW) return@detectTapGestures
                            val beat = scrollBeats + (o.x - labelW) / pxPerBeat
                            val pitch = hi - (o.y / rowH).toInt()
                            val hit = notes.indexOfFirst { it.pitch == pitch && beat >= it.start && beat <= it.end }
                            if (hit >= 0) {
                                selected = hit
                                app.synth.noteOn(notes[hit].pitch, 80, 0.4)
                            } else if (addMode) {
                                snapshot()
                                val start = floor(beat / grid) * grid
                                notes.add(Note(pitch, start, grid, addHand))
                                selected = notes.lastIndex
                                app.synth.noteOn(pitch, 80, 0.4)
                            } else selected = -1
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            scrollBeats = (scrollBeats - drag.x / pxPerBeat).coerceIn(-1.0, max(0.0, meta.lengthBeats))
                        }
                    },
            ) {
                val rows = hi - lo + 1
                val rowH = size.height / rows
                val labelW = 56f
                // rows
                for (p in lo..hi) {
                    val y = (hi - p) * rowH
                    drawRect(if (Pitch.isBlackKey(p)) Color(0xFF12172A) else Color(0xFF181E36), Offset(labelW, y), Size(size.width - labelW, rowH))
                    drawRect(if (Pitch.isBlackKey(p)) PtColors.BlackKey else PtColors.WhiteKey, Offset(0f, y), Size(labelW - 4, rowH - 1))
                    if (p % 12 == 0 && rowH > 9) drawLabel(measurer, Pitch.fullName(p, app.settings.settings.value.noteNaming), Offset(4f, y + rowH / 2 - 9f), Color(0xFF333A55), 10)
                }
                // beats & measures
                val firstBeat = floor(scrollBeats).toInt()
                val lastBeat = (scrollBeats + (size.width - labelW) / pxPerBeat).toInt() + 1
                for (b in firstBeat..lastBeat) {
                    val x = labelW + ((b - scrollBeats) * pxPerBeat).toFloat()
                    if (x < labelW) continue
                    drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
                }
                var m = max(0, meta.measureIndexAt(max(0.0, scrollBeats)))
                while (true) {
                    val b = meta.measureStart(m)
                    val x = labelW + ((b - scrollBeats) * pxPerBeat).toFloat()
                    if (x > size.width) break
                    if (x >= labelW) {
                        drawLine(Color(0x88B388FF), Offset(x, 0f), Offset(x, size.height), 2f)
                        drawLabel(measurer, "${m + 1}", Offset(x + 4f, 2f), Color(0xAAB388FF), 11)
                    }
                    m++
                    if (m > meta.measureCount + 64) break
                }
                // notes
                for ((i, n) in notes.withIndex()) {
                    val x = labelW + ((n.start - scrollBeats) * pxPerBeat).toFloat()
                    val w = (n.duration * pxPerBeat).toFloat()
                    if (x + w < labelW || x > size.width) continue
                    val y = (hi - n.pitch) * rowH
                    drawRoundRect(handColor(n.hand), Offset(max(labelW, x), y + 1), Size(w - 2 - (max(labelW, x) - x), rowH - 2), CornerRadius(4f))
                    if (i == selected) drawRoundRect(Color.White, Offset(x - 2, y - 1), Size(w + 2, rowH + 2), CornerRadius(4f), style = Stroke(3f))
                }
            }
        }

        // selection toolbar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val sel = notes.getOrNull(selected)
                if (sel == null) {
                    Text(
                        if (addMode) "Tippe auf eine freie Stelle, um eine Note einzufügen." else "Tippe auf eine Note, um sie zu bearbeiten. Ziehen = verschieben der Ansicht.",
                        color = PtColors.TextDim,
                    )
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = { snapshot(); val t = notes.map { it.copy(pitch = (it.pitch + 1).coerceAtMost(108)) }; notes.clear(); notes.addAll(t) }) { Text("Alles ½ Ton höher") }
                    TextButton(onClick = { snapshot(); val t = notes.map { it.copy(pitch = (it.pitch - 1).coerceAtLeast(21)) }; notes.clear(); notes.addAll(t) }) { Text("Alles ½ Ton tiefer") }
                    TextButton(onClick = { snapshot(); val t = SongUtils.quantize(notes.toList(), grid); notes.clear(); notes.addAll(t) }) { Text("Am Raster ausrichten") }
                    TextButton(onClick = { meta = meta.copy(beatsPerMeasure = if (meta.beatsPerMeasure >= 6) 2 else meta.beatsPerMeasure + 1); dirty = true }) {
                        Text("Takt: ${meta.beatsPerMeasure}/${meta.beatUnit}")
                    }
                } else {
                    Text("${Pitch.fullName(sel.pitch, app.settings.settings.value.noteNaming)} · Takt ${meta.measureIndexAt(sel.start) + 1} · Länge ${fmt(sel.duration)}", Modifier.width(220.dp))
                    IconButton(onClick = { modify { it.copy(pitch = (it.pitch + 1).coerceAtMost(108)) } }) { Icon(Icons.Filled.ArrowUpward, "½ Ton höher") }
                    IconButton(onClick = { modify { it.copy(pitch = (it.pitch - 1).coerceAtLeast(21)) } }) { Icon(Icons.Filled.ArrowDownward, "½ Ton tiefer") }
                    IconButton(onClick = { modify { it.copy(pitch = (it.pitch + 12).coerceAtMost(108)) } }) { Icon(Icons.Filled.KeyboardDoubleArrowUp, "Oktave höher") }
                    IconButton(onClick = { modify { it.copy(pitch = (it.pitch - 12).coerceAtLeast(21)) } }) { Icon(Icons.Filled.KeyboardDoubleArrowDown, "Oktave tiefer") }
                    IconButton(onClick = { modify { it.copy(start = max(0.0, it.start - grid)) } }) { Icon(Icons.Filled.ChevronLeft, "früher") }
                    IconButton(onClick = { modify { it.copy(start = it.start + grid) } }) { Icon(Icons.Filled.ChevronRight, "später") }
                    IconButton(onClick = { modify { it.copy(duration = max(grid / 2, it.duration - grid)) } }) { Icon(Icons.Filled.Remove, "kürzer") }
                    IconButton(onClick = { modify { it.copy(duration = it.duration + grid) } }) { Icon(Icons.Filled.Add, "länger") }
                    FilterChip(selected = true, onClick = { modify { it.copy(hand = if (it.hand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT) } },
                        label = { Text(if (sel.hand == Hand.RIGHT) "Rechte Hand" else "Linke Hand") })
                    IconButton(onClick = {
                        scope.launch {
                            val chord = notes.filter { kotlin.math.abs(it.start - sel.start) < 0.02 }
                            chord.forEach { app.synth.noteOn(it.pitch, 80, 0.8) }
                            delay(10)
                        }
                    }) { Icon(Icons.Filled.PlayArrow, "Anhören") }
                    IconButton(onClick = { snapshot(); notes.removeAt(selected); selected = -1 }) { Icon(Icons.Filled.Delete, "Löschen", tint = PtColors.Miss) }
                }
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Änderungen speichern?") },
        confirmButton = { TextButton(onClick = { save(); confirmLeave = false; onBack() }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Verwerfen") } },
    )
}

private fun fmt(beats: Double): String = when ((beats * 100).roundToInt()) {
    25 -> "1/16"; 50 -> "1/8"; 75 -> "3/16"; 100 -> "1/4"; 150 -> "3/8"; 200 -> "1/2"; 300 -> "3/4"; 400 -> "1/1"
    else -> "%.2f Schläge".format(beats)
}
