package de.pianotiles.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.pianotiles.app.data.SheetDocument
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.SheetMeasure
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The original PDF next to the falling notes. The current measure is highlighted and kept in view;
 * tapping a measure jumps there.
 */
@Composable
fun SheetPanel(
    /** One PDF or the photo pages ([de.pianotiles.app.data.SongRepository.sheetPages]). */
    pages: List<File>,
    measures: List<SheetMeasure>,
    currentMeasure: Int,
    loopMeasures: IntRange?,
    onSeekMeasure: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Measures to mark in orange (e.g. "Bitte prüfen" in the editor). */
    marked: Set<Int> = emptySet(),
) {
    // keyed on the measures too: an engraved sheet is rewritten in place when the notes change
    val renderer = remember(pages, measures) { SheetDocument.open(pages) }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    val rendered = remember(pages, measures) { mutableStateMapOf<Int, ImageBitmap>() }
    var page by remember { mutableIntStateOf(0) }
    measures.firstOrNull { it.measure == currentMeasure }?.let { if (it.page != page) page = it.page }

    LaunchedEffect(page, renderer) {
        val r = renderer ?: return@LaunchedEffect
        if (rendered.containsKey(page) || page >= r.pageCount) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { runCatching { r.render(page, 1400) }.getOrNull() } ?: return@LaunchedEffect
        rendered[page] = bmp.asImageBitmap()
    }

    BoxWithConstraints(modifier.background(Color.White)) {
        val img = rendered[page]
        if (renderer == null) {
            Text(tr("Notenblatt nicht verfügbar"), Modifier.align(Alignment.Center).padding(16.dp), color = Color.DarkGray)
            return@BoxWithConstraints
        }
        if (img == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = widthPx * img.height / img.width
        val scroll = rememberScrollState()
        val current = measures.firstOrNull { it.measure == currentMeasure && it.page == page }
        LaunchedEffect(current, heightPx) {
            current?.let {
                val target = (it.y0 * heightPx - with(density) { maxHeight.toPx() } * 0.3f).toInt().coerceAtLeast(0)
                scroll.animateScrollTo(target)
            }
        }
        Box(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            Image(img, tr("Notenblatt Seite %s", page + 1), Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            Canvas(
                Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })
                    .pointerInput(page, measures) {
                        detectTapGestures { o ->
                            val nx = o.x / size.width; val ny = o.y / size.height
                            measures.firstOrNull { it.page == page && nx in it.x0..it.x1 && ny in it.y0..it.y1 }?.let { onSeekMeasure(it.measure) }
                        }
                    },
            ) {
                fun rect(m: SheetMeasure) = Offset(m.x0 * size.width, m.y0 * size.height) to Size((m.x1 - m.x0) * size.width, (m.y1 - m.y0) * size.height)
                loopMeasures?.let { range ->
                    for (m in measures) if (m.page == page && m.measure in range) {
                        val (o, s) = rect(m); drawRect(PtColors.Loop.copy(alpha = 0.12f), o, s)
                    }
                }
                for (m in measures) if (m.page == page && m.measure in marked) {
                    val (o, s) = rect(m); drawRect(Color(0x33FF9800), o, s)
                }
                current?.let {
                    val (o, s) = rect(it)
                    drawRoundRect(PtColors.RightHand.copy(alpha = 0.18f), o, s, CornerRadius(8f))
                    drawRoundRect(PtColors.RightHandDark, o, s, CornerRadius(8f), style = Stroke(4f))
                }
            }
        }
        Text(
            tr("Seite %s", page + 1),
            Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xAA0E1220)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White,
        )
    }
}
