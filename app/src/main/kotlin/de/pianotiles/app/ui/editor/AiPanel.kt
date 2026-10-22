package de.pianotiles.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.edit.RelNote
import de.pianotiles.core.model.NoteNaming
import de.pianotiles.core.model.Pitch

/** Progress, live log and the AI's proposals (before/after per measure, accept or reject). */
@Composable
fun AiPanel(vm: EditorViewModel, naming: NoteNaming, onClose: () -> Unit, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = PtColors.Surface) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = PtColors.Loop)
                Spacer(Modifier.width(6.dp))
                Text("KI-nachbessern", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Schließen") }
            }
            LinearProgressIndicator(progress = { vm.aiProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text(vm.aiStatus, style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.aiRunning) OutlinedButton(onClick = vm::cancelAi) { Text("Abbrechen") }
                else OutlinedButton(onClick = onRestart) { Text("Erneut prüfen") }
                val open = vm.proposals.count { it.status == Proposal.Status.OPEN }
                if (open > 0) Button(onClick = vm::acceptAll) { Text("Alle übernehmen ($open)") }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.proposals.isNotEmpty()) item { Text("Vorschläge", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                items(vm.proposals, key = { it.id }) { p -> ProposalCard(vm, p, naming) }
                item { Text("Protokoll", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                items(vm.aiLog.toList().asReversed()) { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim)
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(vm: EditorViewModel, p: Proposal, naming: NoteNaming) {
    val c = p.correction
    val previewing = vm.previewProposal?.id == p.id
    val border = when {
        p.status == Proposal.Status.ACCEPTED -> PtColors.Hit
        p.status == Proposal.Status.REJECTED -> PtColors.Outline
        previewing -> PtColors.Loop
        else -> PtColors.SurfaceHigh
    }
    Column(
        Modifier.fillMaxWidth()
            .background(border.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable { vm.measure = c.measure; vm.previewProposal = if (previewing) null else p }
            .padding(8.dp),
    ) {
        Text("Takt ${c.measure + 1} · ${vm.handName(c.hand)}", fontWeight = FontWeight.Bold)
        if (c.reason.isNotBlank()) Text(c.reason, style = MaterialTheme.typography.bodySmall)
        Text("Vorher: ${notesText(p.before, naming)}", style = MaterialTheme.typography.bodySmall, color = PtColors.Miss.copy(alpha = 0.9f))
        Text("Nachher: ${notesText(c.notes, naming)}", style = MaterialTheme.typography.bodySmall, color = PtColors.Hit)
        when (p.status) {
            Proposal.Status.OPEN -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.measure = c.measure; vm.previewProposal = if (previewing) null else p }) { Text(if (previewing) "Vorschau aus" else "Ansehen") }
                TextButton(onClick = { vm.accept(p) }) { Icon(Icons.Filled.Check, null); Text("Übernehmen") }
                TextButton(onClick = { vm.reject(p) }) { Text("Verwerfen", color = Color(0xFFBBBBBB)) }
            }
            Proposal.Status.ACCEPTED -> Text("✓ übernommen (Rückgängig oben möglich)", style = MaterialTheme.typography.bodySmall, color = PtColors.Hit)
            Proposal.Status.REJECTED -> Text("verworfen", style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim)
        }
    }
}

private fun notesText(ns: List<RelNote>, naming: NoteNaming): String {
    if (ns.isEmpty()) return "(leer)"
    return ns.sortedWith(compareBy({ it.start }, { it.pitch })).groupBy { Math.round(it.start * 48) }.values.joinToString("  ") { chord ->
        chord.joinToString("+") { Pitch.fullName(it.pitch, naming) } + " " + durationSymbol(chord.maxOf { it.duration })
    }
}
