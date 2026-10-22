package de.pianotiles.app.ui.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.data.Sharing
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongSource
import kotlinx.coroutines.launch

private enum class SortOrder(val label: String) { RECENT("Zuletzt geübt"), TITLE("Titel"), NEWEST("Neueste"), DIFFICULTY("Schwierigkeit") }

/** Groups for the filter chips. */
private enum class Shelf(val label: String) {
    ALL("Alle"), FAVORITES("Favoriten"), SHEETS("PDF & Fotos"), FILES("Notendateien"), MIDI("MIDI"), DEMOS("Demos");

    fun matches(s: Song) = when (this) {
        ALL -> true
        FAVORITES -> s.favorite
        SHEETS -> s.source == SongSource.PDF || s.source == SongSource.PHOTO
        FILES -> s.source in setOf(SongSource.MUSICXML, SongSource.ABC, SongSource.MUSESCORE, SongSource.MEI, SongSource.PACKAGE, SongSource.MANUAL)
        MIDI -> s.source == SongSource.MIDI
        DEMOS -> s.source == SongSource.DEMO
    }
}

/** Mime types offered in the file picker. */
val IMPORT_MIME_TYPES = arrayOf(
    "application/pdf", "audio/midi", "audio/x-midi", "audio/mid",
    "application/vnd.recordare.musicxml+xml", "application/vnd.recordare.musicxml",
    "application/xml", "text/xml", "application/zip", "application/octet-stream",
    "text/plain", "text/vnd.abc", "application/x-musescore", "application/mei+xml", "image/*",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onPhotos: () -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PianoTilesApp
    val songs by app.songs.songs.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.RECENT) }
    var shelf by remember { mutableStateOf(Shelf.ALL) }
    var renaming by remember { mutableStateOf<Song?>(null) }
    var deleting by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImport(listOf(it)) } }

    val visible = songs
        .filter { shelf.matches(it) }
        .filter { query.isBlank() || it.title.contains(query, true) || it.composer.contains(query, true) }
        .let { list ->
            when (sort) {
                SortOrder.RECENT -> list.sortedWith(compareByDescending<Song> { it.stats.lastPracticedAt }.thenByDescending { it.createdAt }.thenBy { it.title.lowercase() })
                SortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
                SortOrder.NEWEST -> list.sortedByDescending { it.createdAt }
                SortOrder.DIFFICULTY -> list.sortedWith(compareBy<Song> { it.difficulty }.thenBy { it.title.lowercase() })
            }
        }
    val recent = songs.filter { it.stats.lastPracticedAt > 0 }.sortedByDescending { it.stats.lastPracticedAt }.take(8)
    val selecting = selected.isNotEmpty()
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                Row(Modifier.fillMaxWidth().height(60.dp).background(PtColors.SurfaceHigh).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, tr("Auswahl beenden")) }
                    Text(tr("%s ausgewählt", selected.size), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selected = visible.map { it.id }.toSet() }) { Text(tr("Alle auswählen")) }
                    TextButton(onClick = {
                        val list = songs.filter { it.id in selected }
                        scope.launch { runCatching { Sharing.shareCollection(context, app.songs, list) } }
                    }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text(tr("Teilen")) }
                    TextButton(onClick = { deleting = songs.filter { it.id in selected } }) {
                        Icon(Icons.Filled.Delete, null, tint = PtColors.Miss); Spacer(Modifier.width(4.dp)); Text(tr("Löschen"), color = PtColors.Miss)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(tr("Meine Bibliothek"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(tr("%s Stücke", songs.size), style = MaterialTheme.typography.labelMedium, color = PtColors.TextDim)
                    }
                    Spacer(Modifier.width(24.dp))
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(tr("Suchen …")) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = PtColors.SurfaceHigh, unfocusedContainerColor = PtColors.Surface,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, null); Spacer(Modifier.width(4.dp)); Text(tr(sort.label)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for (o in SortOrder.entries) DropdownMenuItem(text = { Text(tr(o.label)) }, onClick = { sort = o; sortMenu = false })
                        }
                    }
                    IconButton(onClick = onHelp) { Icon(Icons.Filled.HelpOutline, tr("Hilfe")) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, tr("Einstellungen")) }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sh in Shelf.entries) {
                    val count = songs.count { sh.matches(it) }
                    if (count == 0 && sh != Shelf.ALL && sh != shelf) continue
                    FilterChip(
                        selected = shelf == sh, onClick = { shelf = sh },
                        label = { Text(tr(sh.label) + if (sh != Shelf.ALL) " ($count)" else "") },
                        leadingIcon = if (sh == Shelf.FAVORITES) ({ Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp)) }) else null,
                    )
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (songs.isEmpty()) tr("Noch keine Stücke – importiere ein PDF, Fotos, eine MIDI- oder MusicXML-Datei.") else tr("Nichts gefunden."),
                        color = PtColors.TextDim,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(230.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (recent.size >= 2 && query.isBlank() && shelf == Shelf.ALL && !selecting) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(tr("Weiter üben"), style = MaterialTheme.typography.titleSmall, color = PtColors.TextDim)
                                Spacer(Modifier.height(6.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(recent, key = { "r" + it.id }) { s -> RecentChip(s, onClick = { onOpen(s.id) }, onPlay = { onPlay(s.id) }) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(tr("Alle Stücke"), style = MaterialTheme.typography.titleSmall, color = PtColors.TextDim)
                            }
                        }
                    }
                    items(visible, key = { it.id }) { song ->
                        SongCard(
                            song = song,
                            selected = song.id in selected,
                            onClick = { if (selecting) toggle(song.id) else onOpen(song.id) },
                            onLongClick = { toggle(song.id) },
                            onPlay = { onPlay(song.id) },
                            onFavorite = { scope.launch { app.songs.save(song.copy(favorite = !song.favorite)) } },
                            onRename = { renaming = song },
                            onEdit = { onEdit(song.id) },
                            onShare = { scope.launch { runCatching { Sharing.sharePackage(context, app.songs, song) } } },
                            onDelete = { deleting = listOf(song) },
                        )
                    }
                }
            }
        }
        if (!selecting) Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            ExtendedFloatingActionButton(
                onClick = { importMenu = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(tr("Noten importieren")) },
                containerColor = MaterialTheme.colorScheme.primary,
            )
            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                DropdownMenuItem(
                    text = { Column { Text(tr("Datei öffnen")); Text(tr("PDF, MIDI, MusicXML, ABC, MuseScore, MEI, Paket"), fontSize = 11.sp, color = PtColors.TextDim) } },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    onClick = { importMenu = false; picker.launch(IMPORT_MIME_TYPES) },
                )
                DropdownMenuItem(
                    text = { Column { Text(tr("Noten fotografieren")); Text(tr("Mehrere Seiten nacheinander"), fontSize = 11.sp, color = PtColors.TextDim) } },
                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                    onClick = { importMenu = false; onPhotos() },
                )
            }
        }
    }

    renaming?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        var composer by remember(s.id) { mutableStateOf(s.composer) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(tr("Umbenennen")) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(tr("Titel")) }, singleLine = true)
                    OutlinedTextField(composer, { composer = it }, label = { Text(tr("Komponist")) }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { app.songs.save(s.copy(title = title.trim().ifEmpty { s.title }, composer = composer.trim())) }; renaming = null }) { Text(tr("Speichern")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(tr("Abbrechen")) } },
        )
    }
    if (deleting.isNotEmpty()) {
        val list = deleting
        AlertDialog(
            onDismissRequest = { deleting = emptyList() },
            title = { Text(if (list.size == 1) tr("„%s“ löschen?", list[0].title) else tr("%s Stücke löschen?", list.size)) },
            text = { Text(tr("Das Stück und seine Übungsstatistik werden entfernt.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { list.forEach { app.songs.delete(it.id) } }
                    selected = selected - list.map { it.id }.toSet()
                    deleting = emptyList()
                }) { Text(tr("Löschen"), color = PtColors.Miss) }
            },
            dismissButton = { TextButton(onClick = { deleting = emptyList() }) { Text(tr("Abbrechen")) } },
        )
    }
}

@Composable
private fun RecentChip(song: Song, onClick: () -> Unit, onPlay: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(260.dp)) {
        Row(Modifier.clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(song.stats.bestAccuracy, Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("%s Min. geübt", song.stats.practiceSeconds / 60), fontSize = 11.sp, color = PtColors.TextDim)
            }
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, tr("Abspielen"), tint = PtColors.RightHand) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongCard(
    song: Song, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onPlay: () -> Unit, onFavorite: () -> Unit,
    onRename: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PianoTilesApp
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var cover by remember(song.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.id) { cover = runCatching { app.songs.cover(song.id) }.getOrNull() }
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .then(if (selected) Modifier.border(3.dp, PtColors.RightHand, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF0B0F1C))) {
                val c = cover
                if (c != null) {
                    Image(c.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                } else MiniRoll(song, Modifier.fillMaxSize().padding(8.dp))
                Row(Modifier.align(Alignment.TopEnd)) {
                    if (selected) Icon(Icons.Filled.CheckCircle, null, tint = PtColors.RightHand, modifier = Modifier.padding(12.dp))
                    IconButton(onClick = onFavorite) {
                        Icon(if (song.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, tr("Favorit"), tint = if (song.favorite) PtColors.Miss else PtColors.TextDim)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Mehr")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(tr("Abspielen")) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { menu = false; onPlay() })
                            DropdownMenuItem(text = { Text(tr("Umbenennen")) }, leadingIcon = { Icon(Icons.Filled.TextFields, null) }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text(tr("Noten bearbeiten")) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                            DropdownMenuItem(text = { Text(tr("Stück teilen (Paket)")) }, leadingIcon = { Icon(Icons.Filled.Inventory2, null) }, onClick = { menu = false; onShare() })
                            DropdownMenuItem(text = { Text(tr("Als MusicXML teilen")) }, leadingIcon = { Icon(Icons.Filled.Description, null) }, onClick = {
                                menu = false; scope.launch { runCatching { Sharing.shareMusicXml(context, song) } }
                            })
                            DropdownMenuItem(text = { Text(tr("Als MIDI teilen")) }, leadingIcon = { Icon(Icons.Filled.MusicNote, null) }, onClick = {
                                menu = false; runCatching { Sharing.shareMidi(context, song) }
                            })
                            DropdownMenuItem(text = { Text(tr("Löschen")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
                SourceBadge(song.source, Modifier.align(Alignment.BottomStart).padding(8.dp))
                Surface(
                    onClick = onPlay, shape = CircleShape, color = PtColors.RightHand,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
                ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, tr("Abspielen"), tint = Color.White) } }
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.composer.ifBlank { "–" }, style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(5) { i ->
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < song.difficulty) PtColors.LeftHand else PtColors.Outline))
                            Spacer(Modifier.width(3.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(tr("%s Takte", song.measureCount), fontSize = 11.sp, color = PtColors.TextDim)
                    }
                }
                ProgressRing(song.stats.bestAccuracy, Modifier.size(40.dp))
            }
        }
    }
}

/** Best accuracy as a ring (empty when never practised). */
@Composable
private fun ProgressRing(percent: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.12f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(PtColors.Outline, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (percent > 0) drawArc(
                if (percent >= 90) PtColors.Hit else PtColors.LeftHand, -90f, 360f * percent / 100f, false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(if (percent > 0) "$percent" else "–", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceBadge(source: SongSource, modifier: Modifier) {
    val label = when (source) {
        SongSource.DEMO -> "Demo"; SongSource.MIDI -> "MIDI"; SongSource.MUSICXML -> "MusicXML"
        SongSource.PDF -> "PDF"; SongSource.MANUAL -> tr("Eigen"); SongSource.PHOTO -> tr("Foto")
        SongSource.ABC -> "ABC"; SongSource.MUSESCORE -> "MuseScore"; SongSource.MEI -> "MEI"; SongSource.PACKAGE -> tr("Paket")
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
