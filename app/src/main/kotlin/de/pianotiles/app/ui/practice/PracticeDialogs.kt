package de.pianotiles.app.ui.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors

@Composable
fun SpeedDialog(vm: PracticeViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fertig")) } },
        title = { Text("Tempo") },
        text = {
            Column {
                Text("${(vm.speed * 100).toInt()} %  ·  ${(vm.song.bpm * vm.speed).toInt()} BPM", style = MaterialTheme.typography.headlineSmall)
                Slider(value = vm.speed, onValueChange = vm::changeSpeed, valueRange = 0.1f..1.5f, steps = 27)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f)) {
                        FilterChip(selected = vm.speed == p, onClick = { vm.changeSpeed(p) }, label = { Text("${(p * 100).toInt()} %") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Tipp: Im Modus „Warten“ bestimmst du das Tempo selbst – die Noten bleiben stehen, bis du sie spielst."),
                    style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
                )
            }
        },
    )
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(90.dp))
        IconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, tr("weniger")) }
        Text("$value", Modifier.width(36.dp), fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, tr("mehr")) }
    }
}

/**
 * Everything about repeating a passage: range, repetitions, the tempo ramp ("Tempo-Treppe"),
 * saved sections and suggested difficult measures.
 */
@Composable
fun LoopPanel(vm: PracticeViewModel, onDismiss: () -> Unit) {
    var sectionName by remember { mutableStateOf("") }
    val loop = vm.loop
    val hasRange = loop.endBeat > loop.startBeat
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fertig")) } },
        dismissButton = {
            if (hasRange) TextButton(onClick = { vm.updateLoop(loop.copy(enabled = !loop.enabled)) }) {
                Text(if (loop.enabled) tr("Schleife aus") else tr("Schleife an"))
            }
        },
        title = { Text(tr("Übungsschleife")) },
        modifier = Modifier.widthIn(max = 640.dp),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    tr("Tipp: Ziehe mit dem Finger über die Zeitleiste unten, um Takte zu markieren. Doppeltippen auf die Noten wiederholt den aktuellen Takt."),
                    style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                val a = if (hasRange) vm.loopStartMeasure + 1 else vm.engine.currentMeasure + 1
                val b = if (hasRange) vm.loopEndMeasure + 1 else a
                Row {
                    Column(Modifier.weight(1f)) {
                        Stepper(tr("Von Takt"), a) { vm.setLoopMeasures(it - 1, maxOf(it, b) - 1) }
                        Stepper(tr("Bis Takt"), b) { vm.setLoopMeasures(minOf(a, it) - 1, it - 1) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Wiederholungen"), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((n, label) in listOf(0 to "∞", 3 to "3×", 5 to "5×", 10 to "10×")) {
                                FilterChip(selected = loop.repetitions == n, onClick = { vm.updateLoop(loop.copy(repetitions = n)) }, label = { Text(label) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Tempo-Treppe"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("Startet langsamer und wird nach jedem Durchgang mit ≥ %s %% Treffern um %s %% schneller – bis %s %%.", loop.rampThreshold, (loop.rampStep * 100).toInt(), (loop.rampTarget * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
                        )
                    }
                    Switch(checked = loop.tempoRamp, onCheckedChange = { vm.updateLoop(loop.copy(tempoRamp = it, enabled = true)) })
                }
                if (loop.tempoRamp) {
                    Text(tr("Zieltempo: %s %%", (loop.rampTarget * 100).toInt()))
                    Slider(value = loop.rampTarget.toFloat(), onValueChange = { vm.updateLoop(loop.copy(rampTarget = (it * 20).toInt() / 20.0)) }, valueRange = 0.5f..1.5f, steps = 19)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (st in listOf(0.05, 0.1)) FilterChip(selected = loop.rampStep == st, onClick = { vm.updateLoop(loop.copy(rampStep = st)) }, label = { Text(tr("+%s %% je Runde", (st * 100).toInt())) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(tr("Abschnitte"), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in vm.song.sections) {
                        InputChip(
                            selected = loop.enabled && loop.startBeat == s.startBeat && loop.endBeat == s.endBeat,
                            onClick = { vm.selectSection(s) },
                            label = { Text(s.name) },
                            trailingIcon = { Icon(Icons.Filled.Close, tr("Abschnitt löschen"), Modifier.clickable { vm.deleteSection(s) }) },
                        )
                    }
                    if (vm.song.sections.isEmpty()) Text(tr("Noch keine gespeichert."), color = PtColors.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                if (hasRange) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(sectionName, { sectionName = it }, label = { Text(tr("Name, z. B. „Refrain“")) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.saveLoopAsSection(sectionName); sectionName = "" }) { Text(tr("Speichern")) }
                }
                val difficult = vm.difficultMeasures()
                if (difficult.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Schwierige Takte (die meisten Fehler)"), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in difficult) FilterChip(selected = false, onClick = { vm.setLoopMeasures(m, m) }, label = { Text(tr("Takt %s", m + 1)) })
                    }
                }
            }
        },
    )
}

@Composable
fun FinishedDialog(vm: PracticeViewModel, info: FinishInfo, onBack: () -> Unit) {
    val praise = when {
        info.accuracy >= 95 -> tr("Großartig! 🎉")
        info.accuracy >= 80 -> tr("Sehr gut!")
        info.accuracy >= 60 -> tr("Gut – weiter so!")
        else -> tr("Übung macht den Meister")
    }
    AlertDialog(
        onDismissRequest = { vm.finished = null },
        title = { Text(praise) },
        text = {
            Column {
                Text(tr("%s %% richtig", info.accuracy), style = MaterialTheme.typography.headlineMedium, color = PtColors.Hit)
                Text(tr("Dauer: %s:%s min", info.seconds / 60, "%02d".format(info.seconds % 60)), color = PtColors.TextDim)
                info.worstMeasure?.let { Text(tr("Die meisten Fehler in Takt %s.", it + 1), color = PtColors.TextDim) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.finished = null; vm.restart(); vm.togglePlay() }) { Text(tr("Nochmal")) } },
        dismissButton = {
            Row {
                info.worstMeasure?.let { m ->
                    TextButton(onClick = {
                        vm.finished = null
                        vm.setLoopMeasures(m, m)
                        vm.updateLoop(vm.loop.copy(tempoRamp = true, rampTarget = 1.0))
                    }) { Text(tr("Takt %s üben", m + 1)) }
                }
                TextButton(onClick = { vm.finished = null; onBack() }) { Text(tr("Bibliothek")) }
            }
        },
    )
}
