package de.pianotiles.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import kotlinx.coroutines.launch

private enum class SortOrder(val label: String) { RECENT("Zuletzt geübt"), TITLE("Titel"), NEWEST("Neueste") }

/** Mime types offered in the file picker. */
val IMPORT_MIME_TYPES = arrayOf(
    "application/pdf", "audio/midi", "audio/x-midi", "audio/mid",
    "application/vnd.recordare.musicxml+xml", "application/vnd.recordare.musicxml",
    "application/xml", "text/xml", "application/zip", "application/octet-stream",
)

@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val songs by app.songs.songs.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.RECENT) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Song?>(null) }
    var deleting by remember { mutableStateOf<Song?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }

    val visible = songs
        .filter { !favoritesOnly || it.favorite }
        .filter { query.isBlank() || it.title.contains(query, true) || it.composer.contains(query, true) }
        .let { list ->
            when (sort) {
                SortOrder.RECENT -> list.sortedWith(compareByDescending<Song> { it.stats.lastPracticedAt }.thenBy { it.title.lowercase() })
                SortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
                SortOrder.NEWEST -> list.sortedByDescending { it.createdAt }
            }
        }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Meine Bibliothek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${songs.size} Stücke", style = MaterialTheme.typography.labelMedium, color = PtColors.TextDim)
                }
                Spacer(Modifier.width(24.dp))
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Suchen …") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = PtColors.SurfaceHigh, unfocusedContainerColor = PtColors.Surface,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                for (o in SortOrder.entries) {
                    FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(o.label) }, modifier = Modifier.padding(end = 6.dp))
                }
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("Favoriten") },
                    leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp)) })
                IconButton(onClick = onHelp) { Icon(Icons.Filled.HelpOutline, "Hilfe") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Einstellungen") }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (songs.isEmpty()) "Noch keine Stücke – importiere ein PDF, eine MIDI- oder MusicXML-Datei." else "Nichts gefunden.",
                        color = PtColors.TextDim,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(230.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.id }) { song ->
                        SongCard(
                            song = song,
                            onClick = { onOpen(song.id) },
                            onFavorite = { scope.launch { app.songs.save(song.copy(favorite = !song.favorite)) } },
                            onRename = { renaming = song },
                            onEdit = { onEdit(song.id) },
                            onDelete = { deleting = song },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picker.launch(IMPORT_MIME_TYPES) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Noten importieren") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        )
    }

    renaming?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        var composer by remember(s.id) { mutableStateOf(s.composer) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Umbenennen") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true)
                    OutlinedTextField(composer, { composer = it }, label = { Text("Komponist") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { app.songs.save(s.copy(title = title.trim().ifEmpty { s.title }, composer = composer.trim())) }; renaming = null }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("„${s.title}“ löschen?") },
            text = { Text("Das Stück und seine Übungsstatistik werden entfernt.") },
            confirmButton = { TextButton(onClick = { scope.launch { app.songs.delete(s.id) }; deleting = null }) { Text("Löschen", color = PtColors.Miss) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit, onFavorite: () -> Unit, onRename: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            Box(Modifier.fillMaxWidth().height(86.dp).background(Color(0xFF0B0F1C))) {
                MiniRoll(song, Modifier.fillMaxSize().padding(8.dp))
                Row(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = onFavorite) {
                        Icon(if (song.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorit", tint = if (song.favorite) PtColors.Miss else PtColors.TextDim)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Umbenennen") }, leadingIcon = { Icon(Icons.Filled.TextFields, null) }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Noten bearbeiten") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                            DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
                SourceBadge(song.source, Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            Column(Modifier.padding(12.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.composer.ifBlank { "–" }, style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) { i ->
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < song.difficulty) PtColors.LeftHand else PtColors.Outline))
                        Spacer(Modifier.width(3.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${song.measureCount} Takte", fontSize = 11.sp, color = PtColors.TextDim)
                    Spacer(Modifier.weight(1f))
                    if (song.stats.bestAccuracy > 0) Text("Best: ${song.stats.bestAccuracy} %", fontSize = 11.sp, color = PtColors.Hit)
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(source: SongSource, modifier: Modifier) {
    val label = when (source) {
        SongSource.DEMO -> "Demo"; SongSource.MIDI -> "MIDI"; SongSource.MUSICXML -> "MusicXML"
        SongSource.PDF -> "PDF"; SongSource.MANUAL -> "Eigen"
    }
    Text(label, modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xAA222944)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
}

/** Small piano-roll preview of the first measures. */
@Composable
private fun MiniRoll(song: Song, modifier: Modifier) {
    Canvas(modifier) {
        if (song.notes.isEmpty()) return@Canvas
        val beats = minOf(song.lengthBeats, song.measureLength * 8).coerceAtLeast(1.0)
        val lo = song.lowestPitch - 1; val hi = song.highestPitch + 1
        val ph = size.height / (hi - lo + 1)
        for (n in song.notes) {
            if (n.start > beats) continue
            val x = (n.start / beats * size.width).toFloat()
            val w = (n.duration / beats * size.width).toFloat().coerceAtLeast(2f)
            val y = (hi - n.pitch) * ph
            drawRect(if (n.hand == Hand.RIGHT) PtColors.RightHand else PtColors.LeftHand, Offset(x, y), Size(w, ph.coerceAtLeast(2f)))
        }
    }
}
