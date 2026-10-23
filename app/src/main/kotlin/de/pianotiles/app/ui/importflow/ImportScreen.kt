package de.pianotiles.app.ui.importflow

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.omr.Clef
import de.pianotiles.core.omr.OmrOptions
import de.pianotiles.core.omr.PageDiagnostics
import de.pianotiles.core.omr.StaffLayout

@Composable
fun ImportScreen(uris: List<Uri>, onBack: () -> Unit, onDone: (songId: String, edit: Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ImportViewModel = viewModel(key = "import-${uris.joinToString()}") { ImportViewModel(app, uris) }
    val state = vm.state
    LaunchedEffect(state) { if (state is ImportState.Done) onDone(state.songId, state.edit) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
            Text(tr("Noten importieren"), style = MaterialTheme.typography.titleMedium)
        }
        when (state) {
            is ImportState.Working -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(state.message)
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.width(280.dp).padding(top = 12.dp)) }
                }
            }
            is ImportState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(520.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = PtColors.Miss)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text(tr("Zurück")) }
                }
            }
            is ImportState.Review -> ReviewContent(vm, state)
            is ImportState.Done -> Unit
        }
    }
}

@Composable
private fun ReviewContent(vm: ImportViewModel, review: ImportState.Review) {
    val result = review.result
    var title by remember(review) { mutableStateOf(review.options.title) }
    var bpm by remember(review) { mutableFloatStateOf(review.options.bpm.toFloat()) }
    var options by remember(review) { mutableStateOf(review.options) }
    val changed = options != review.options

    Row(Modifier.fillMaxSize()) {
        // Pages with recognised notes
        LazyColumn(Modifier.weight(0.62f).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(review.previews) { i, bmp ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                    Image(bmp.asImageBitmap(), tr("Seite %s", i + 1), Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    result.pages.getOrNull(i)?.let { page -> Overlay(page, Modifier.matchParentSize()) }
                }
            }
        }
        // Settings & summary
        Surface(Modifier.weight(0.38f).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                val notes = result.song.notes.size
                Text(tr("%s Noten in %s Takten erkannt", notes, result.song.measureCount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Blau = rechte Hand (Violinschlüssel), Orange = linke Hand (Bassschlüssel). Fehlt etwas oder ist falsch, kannst du es nach dem Speichern im Noten-Editor korrigieren."),
                    style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
                )
                if (result.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    for (w in result.warnings) Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = PtColors.LeftHand)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(title, { title = it }, label = { Text(tr("Titel")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(tr("Tempo: %s BPM (Viertel)", bpm.toInt()))
                Slider(bpm, { bpm = it }, valueRange = 30f..200f)

                Section(tr("Notensystem"))
                ChipRow(
                    listOf(StaffLayout.AUTO to tr("Automatisch"), StaffLayout.PIANO to tr("Klavier (2 Zeilen)"), StaffLayout.SINGLE_TREBLE to tr("Nur Violinschl."), StaffLayout.SINGLE_BASS to tr("Nur Bassschl.")),
                    options.layout,
                ) { options = options.copy(layout = it) }

                Section(tr("Tonart (erkannt: %s)", Pitch.keyName(result.keyFifths)))
                ChipRow(
                    listOf<Pair<Int?, String>>(null to tr("Automatisch")) + (-6..6).map { it to keyShort(it) },
                    options.keyFifths,
                ) { options = options.copy(keyFifths = it) }

                Section(tr("Taktart (erkannt: %s/%s)", result.beatsPerMeasure, result.beatUnit))
                ChipRow(
                    listOf<Pair<Pair<Int, Int>?, String>>(null to tr("Automatisch"), (2 to 4) to "2/4", (3 to 4) to "3/4", (4 to 4) to "4/4", (3 to 8) to "3/8", (6 to 8) to "6/8"),
                    options.beatsPerMeasure?.let { it to options.beatUnit },
                ) { options = if (it == null) options.copy(beatsPerMeasure = null, beatUnit = 4) else options.copy(beatsPerMeasure = it.first, beatUnit = it.second) }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.recognize(options.copy(title = title, bpm = bpm.toDouble())) }, enabled = changed, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(tr("Mit diesen Einstellungen neu erkennen"))
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.save(review, title, bpm.toDouble(), edit = true) }, enabled = notes > 0, modifier = Modifier.weight(1f)) { Text(tr("Speichern & korrigieren")) }
                    Button(onClick = { vm.save(review, title, bpm.toDouble(), edit = false) }, enabled = notes > 0, modifier = Modifier.weight(1f)) { Text(tr("Speichern & üben")) }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    tr("Am besten funktionieren sauber gesetzte Noten (z. B. aus MuseScore, LilyPond oder Verlagsausgaben). Bei Scans: gerade und kontrastreich scannen. Für perfekte Ergebnisse kannst du auch MusicXML oder MIDI importieren."),
                    style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
                )
            }
        }
    }
}

private fun keyShort(f: Int) = when {
    f == 0 -> "C / a"
    f > 0 -> "$f♯"
    else -> "${-f}♭"
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun <T> ChipRow(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in items) FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
    }
}

@Composable
private fun Overlay(page: PageDiagnostics, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.width / page.width
        for (st in page.staves) {
            drawRect(Color(0x2200A0FF), Offset(st.xStart * s, st.top.toFloat() * s), Size((st.xEnd - st.xStart) * s, (st.bottom - st.top).toFloat() * s))
        }
        for ((x, r) in page.barlines) {
            drawLine(Color(0x99B388FF), Offset(x * s, r.first * s), Offset(x * s, r.last * s), 3f)
        }
        for (h in page.heads) {
            val c = if (h.clef == Clef.TREBLE) PtColors.RightHandDark else PtColors.LeftHandDark
            drawRect(c, Offset(h.x0 * s - 2, h.y0 * s - 2), Size((h.x1 - h.x0) * s + 4, (h.y1 - h.y0) * s + 4), style = Stroke(2.5f))
        }
    }
}
