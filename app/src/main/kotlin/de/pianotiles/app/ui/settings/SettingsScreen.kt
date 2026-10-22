package de.pianotiles.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.i18n.I18n
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.ai.ClaudeReviewer
import de.pianotiles.core.model.NoteNaming
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PianoTilesApp
    val s by app.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? android.app.Activity
    fun upd(f: (de.pianotiles.app.data.AppSettings) -> de.pianotiles.app.data.AppSettings) = app.settings.update(f)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
            Text(tr("Einstellungen"), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 760.dp)) {
            Header("Sprache / Language")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((code, label) in listOf("auto" to tr("Automatisch"), "de" to "Deutsch", "en" to "English")) {
                    FilterChip(
                        selected = s.language == code,
                        onClick = {
                            if (s.language != code) {
                                upd { it.copy(language = code) }
                                I18n.apply(code)
                                // Re-create the activity so every screen is rendered in the new language.
                                activity?.recreate()
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }

            Header(tr("Anzeige"))
            Text(tr("Notennamen"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((n, label) in listOf(NoteNaming.GERMAN to tr("Deutsch (C D E … H)"), NoteNaming.ENGLISH to tr("Englisch (… B)"), NoteNaming.SOLFEGE to "Do Re Mi", NoteNaming.NONE to tr("Keine"))) {
                    FilterChip(selected = s.noteNaming == n, onClick = { upd { it.copy(noteNaming = n) } }, label = { Text(label) })
                }
            }
            Toggle(tr("Namen auf den fallenden Noten"), s.showNamesOnTiles) { v -> upd { it.copy(showNamesOnTiles = v) } }
            Toggle(tr("Namen auf den Tasten"), s.showNamesOnKeys) { v -> upd { it.copy(showNamesOnKeys = v) } }
            Toggle(tr("Taktnummern"), s.showMeasureNumbers) { v -> upd { it.copy(showMeasureNumbers = v) } }
            SliderRow(tr("Vorschau / Zoom: %.1f s sichtbar", s.lookaheadSeconds), s.lookaheadSeconds, 1.5f..8f) { v -> upd { it.copy(lookaheadSeconds = v) } }

            Header(tr("Üben"))
            Toggle(tr("Einzählen (1 Takt) vor dem Start"), s.countIn) { v -> upd { it.copy(countIn = v) } }
            Toggle(tr("Andere Hand automatisch mitspielen"), s.accompaniment) { v -> upd { it.copy(accompaniment = v) } }
            Toggle(tr("Metronom standardmäßig an"), s.metronome) { v -> upd { it.copy(metronome = v) } }
            SliderRow(tr("Lautstärke: %s %%", (s.volume * 100).toInt()), s.volume, 0f..1f) { v -> upd { it.copy(volume = v) }; app.synth.volume = v }

            Header(tr("Mikrofon"))
            SliderRow(tr("Empfindlichkeit: %s %%", (s.micSensitivity * 100).toInt()), s.micSensitivity, 0.1f..0.95f) { v -> upd { it.copy(micSensitivity = v) } }
            Text(
                tr("Höher = auch leise Töne werden erkannt (aber eher Fehlalarme durch Geräusche). Bei Digitalpianos über Kopfhörer hört das Mikrofon nichts – dann bitte Lautsprecher verwenden."),
                style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
            )
            Toggle(tr("Oktavfehler tolerieren (empfohlen)"), s.octaveTolerant) { v -> upd { it.copy(octaveTolerant = v) } }

            Header(tr("KI-nachbessern (optional)"))
            var keyInput by remember { mutableStateOf("") }
            var hasKey by remember { mutableStateOf(!app.secrets.apiKey.isNullOrBlank()) }
            var model by remember { mutableStateOf(app.secrets.aiModel) }
            Text(
                tr("Im Noten-Editor kann eine KI (Claude von Anthropic) importierte Stücke mit dem Original-PDF vergleichen und Korrekturen vorschlagen. Dafür brauchst du einen eigenen API-Schlüssel (console.anthropic.com → API Keys). Der Schlüssel wird verschlüsselt (Android-Keystore) nur auf diesem Gerät gespeichert. Gesendet werden nur die Seitenbilder und Noten des Stücks, das du prüfen lässt – und erst, wenn du im Editor auf „KI-nachbessern“ tippst."),
                style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim,
            )
            if (hasKey) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("✓ API-Schlüssel ist gespeichert"), color = PtColors.Hit, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { app.secrets.apiKey = null; hasKey = false }) { Text(tr("Schlüssel löschen")) }
                }
            } else {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(tr("API-Schlüssel (sk-ant-…)")) }, visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedButton(onClick = { app.secrets.apiKey = keyInput; keyInput = ""; hasKey = !app.secrets.apiKey.isNullOrBlank() }, enabled = keyInput.isNotBlank()) {
                        Text(tr("Speichern"))
                    }
                }
            }
            OutlinedTextField(
                value = model, onValueChange = { model = it; if (it.isNotBlank()) app.secrets.aiModel = it }, singleLine = true,
                label = { Text(tr("Modell")) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                supportingText = { Text(tr("Standard: %s (nur ändern, wenn du ein anderes Claude-Modell nutzen möchtest)", ClaudeReviewer.DEFAULT_MODEL)) },
            )

            Header(tr("Bibliothek"))
            val context = LocalContext.current
            var info by remember { mutableStateOf<String?>(null) }
            val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri != null) scope.launch {
                    info = runCatching { context.contentResolver.openOutputStream(uri)!!.use { app.songs.backup(it) } }
                        .fold({ tr("%s Stücke gesichert.", it) }, { tr("Sicherung fehlgeschlagen: %s", it.message) })
                }
            }
            val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    info = runCatching { context.contentResolver.openInputStream(uri)!!.use { app.songs.restore(it) } }
                        .fold({ tr("%s Stücke wiederhergestellt.", it) }, { tr("Wiederherstellen fehlgeschlagen: %s", it.message) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backupLauncher.launch(tr("PianoTiles-Sicherung.zip")) }) { Text(tr("Bibliothek sichern (ZIP)")) }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(tr("Sicherung laden")) }
                OutlinedButton(onClick = { scope.launch { app.songs.restoreDemos() } }) { Text(tr("Demo-Stücke wiederherstellen")) }
            }
            info?.let { Text(it, color = PtColors.Hit, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(32.dp))
            Text(tr("PianoTiles 0.2 · Alle Daten bleiben auf deinem Gerät (außer beim freiwilligen KI-nachbessern)."), style = MaterialTheme.typography.bodySmall, color = PtColors.TextDim)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Header(t: String) {
    Spacer(Modifier.height(20.dp))
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, Modifier.padding(top = 8.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
