package de.pianotiles.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.practice.Course
import de.pianotiles.core.practice.HandSelection
import de.pianotiles.core.practice.Lesson
import de.pianotiles.core.practice.PracticeMode

private val Gold = Color(0xFFFFC94D)

/** The learning path: all lessons with their stars; the next lesson not yet passed is highlighted. */
@Composable
fun CourseScreen(onBack: () -> Unit, onOpen: (Lesson) -> Unit) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val stars by app.practiceLog.stars.collectAsState()
    val next = Course.next { stars[it] ?: 0 }
    val total = Course.lessons.sumOf { stars[it.id] ?: 0 }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
            Column(Modifier.weight(1f)) {
                Text(tr("Lernpfad"), style = MaterialTheme.typography.titleMedium)
                Text(tr("%s von %s Sternen", total, Course.lessons.size * 3), style = MaterialTheme.typography.labelSmall, color = PtColors.TextDim)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Course.lessons, key = { it.id }) { lesson ->
                val n = Course.lessons.indexOf(lesson) + 1
                LessonRow(n, lesson, stars[lesson.id] ?: 0, lesson == next, onClick = { onOpen(lesson) })
            }
        }
    }
}

@Composable
private fun LessonRow(number: Int, lesson: Lesson, stars: Int, isNext: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (isNext) PtColors.SurfaceHigh else PtColors.Surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)
            .then(if (isNext) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(if (stars > 0) Gold else PtColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$number", fontWeight = FontWeight.Bold, color = if (stars > 0) Color.Black else Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lesson.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(lesson.tip, style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim)
                Text(
                    listOf(
                        when (lesson.hands) { HandSelection.RIGHT -> tr("rechte Hand"); HandSelection.LEFT -> tr("linke Hand"); HandSelection.BOTH -> tr("beide Hände") },
                        when (lesson.mode) { PracticeMode.WAIT -> tr("Warten"); PracticeMode.FLOW -> tr("Fließend"); PracticeMode.LISTEN -> tr("Anhören") },
                        "${(lesson.speed * 100).toInt()} %",
                        tr("Ziel %s %%", lesson.goal),
                    ).joinToString(" · "),
                    fontSize = 11.sp, color = PtColors.TextDim,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("★".repeat(stars) + "☆".repeat(3 - stars), color = Gold, fontSize = 16.sp)
                IconButton(onClick = onClick) { Icon(Icons.Filled.PlayArrow, tr("Lektion starten"), tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/** Compact card in the library: the next lesson of the learning path. */
@Composable
fun CourseCard(onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val stars by app.practiceLog.stars.collectAsState()
    val next = Course.next { stars[it] ?: 0 }
    val number = Course.lessons.indexOf(next) + 1
    Surface(color = PtColors.SurfaceHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.School, null, tint = Gold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Lernpfad"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(tr("Lektion %s: %s", number, next.title), style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim, maxLines = 1)
            }
            Text("${Course.lessons.sumOf { stars[it.id] ?: 0 }} ★", color = Gold)
        }
    }
}
