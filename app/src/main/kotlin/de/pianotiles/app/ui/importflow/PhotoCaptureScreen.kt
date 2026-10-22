package de.pianotiles.app.ui.importflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Noten fotografieren": take several photos one after another (or pick them from the gallery), put them in
 * order, then recognise them like a PDF.
 */
@Composable
fun PhotoCaptureScreen(onBack: () -> Unit, onRecognize: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val photos = remember { mutableStateListOf<Uri>() }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pending?.let(Uri::parse)
        if (ok && uri != null) photos.add(uri)
        pending = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> photos.addAll(uris) }

    fun takePhoto() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        pending = uri.toString()
        runCatching { camera.launch(uri) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
            Text(tr("Noten fotografieren"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onRecognize(photos.toList()) }, enabled = photos.isNotEmpty(), modifier = Modifier.padding(end = 12.dp)) {
                Text(tr("Noten erkennen (%s Fotos)", photos.size))
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(280.dp)) {
                Button(onClick = ::takePhoto, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(tr("Foto aufnehmen"))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text(tr("Aus der Galerie")) }
                Spacer(Modifier.height(20.dp))
                Text(tr("Tipps für gute Fotos"), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                for (tip in listOf(
                    tr("Seite flach hinlegen, von oben fotografieren – das ganze Blatt aufs Bild."),
                    tr("Helles, gleichmäßiges Licht, keine harten Schatten der Hand oder des Handys."),
                    tr("Eine Seite pro Foto, in der richtigen Reihenfolge (mit ◀ ▶ änderbar)."),
                    tr("Leichte Schräglage und dunkler Tisch drumherum werden automatisch korrigiert."),
                )) Text("• $tip", style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim, modifier = Modifier.padding(vertical = 2.dp))
            }
            if (photos.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr("Noch keine Fotos. Nimm die erste Seite auf."), color = PtColors.TextDim)
                }
            } else {
                LazyRow(Modifier.weight(1f).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(photos, key = { _, u -> u.toString() }) { i, uri ->
                        PhotoCard(
                            context, uri, i,
                            onLeft = if (i > 0) ({ photos.add(i - 1, photos.removeAt(i)) }) else null,
                            onRight = if (i < photos.lastIndex) ({ photos.add(i + 1, photos.removeAt(i)) }) else null,
                            onDelete = { photos.removeAt(i) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoCard(context: Context, uri: Uri, index: Int, onLeft: (() -> Unit)?, onRight: (() -> Unit)?, onDelete: () -> Unit) {
    var thumb by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { thumb = withContext(Dispatchers.IO) { loadThumb(context, uri, 600) } }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.width(240.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                thumb?.let { Image(it.asImageBitmap(), tr("Seite %s", index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onLeft?.invoke() }, enabled = onLeft != null) { Icon(Icons.Filled.ChevronLeft, tr("Nach vorne")) }
                Text(tr("Seite %s", index + 1), Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { onRight?.invoke() }, enabled = onRight != null) { Icon(Icons.Filled.ChevronRight, tr("Nach hinten")) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, tr("Entfernen"), Modifier.size(20.dp)) }
            }
        }
    }
}

private fun loadThumb(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return@runCatching null
    // phone cameras store portrait photos sideways plus an EXIF rotation
    val rotation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    if (rotation == 0) bmp
    else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
}.getOrNull()
