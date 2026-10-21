package de.pianotiles.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.pianotiles.app.ui.theme.PtColors

private val topics = listOf(
    "Die drei Modi" to "• Warten: Die Noten bleiben an der Linie stehen, bis du die richtigen Tasten spielst. Ideal zum Lernen – du bestimmst das Tempo.\n" +
        "• Fließend: Die Noten laufen im eingestellten Tempo durch. Mit Mikrofon wird mitgezählt, wie viele du triffst.\n" +
        "• Anhören: Die App spielt das Stück vor.",
    "Hände getrennt üben" to "Wähle oben L (links), R (rechts) oder L+R. Die andere Hand wird leiser angezeigt und – wenn eingeschaltet – automatisch mitgespielt.",
    "Teile wiederholen (Schleife)" to "• Ziehe mit dem Finger über die Zeitleiste unten, um Takte zu markieren – die Schleife startet sofort.\n" +
        "• Doppeltippe auf die fallenden Noten, um den aktuellen Takt zu wiederholen.\n" +
        "• Über den Schleifen-Knopf: Anzahl Wiederholungen, Tempo-Treppe (wird nach jedem guten Durchgang schneller), Abschnitte speichern und schwierige Takte direkt üben.",
    "Steuerung" to "Tippen auf die Noten = Start/Pause. Senkrecht wischen = vor- und zurückspulen. ⏪/⏩ springen taktweise. Die Tasten unten kann man auch antippen – praktisch ohne Klavier.",
    "Mikrofon" to "Lege das Gerät auf das Notenpult. In ruhiger Umgebung funktioniert die Erkennung am besten. Die Empfindlichkeit lässt sich in den Einstellungen anpassen.",
    "PDF importieren" to "Tippe in der Bibliothek auf „Noten importieren“ und wähle ein PDF. Die App erkennt Notenlinien, Noten, Vorzeichen, Pausen und Rhythmus und zeigt das Ergebnis farbig über dem Notenblatt an. " +
        "Tonart, Taktart und Notensystem kannst du dort korrigieren und neu erkennen lassen. Kleine Fehler behebst du im Noten-Editor. " +
        "Am zuverlässigsten: digital gesetzte Noten. Alternativ MusicXML (.musicxml/.mxl) oder MIDI importieren.",
    "Noten-Editor" to "Tippe eine Note an und ändere Tonhöhe, Position, Länge oder Hand. Im Modus „Hinzufügen“ setzt ein Tipp auf eine freie Stelle eine neue Note. Rückgängig ist jederzeit möglich.",
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Hilfe", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 820.dp)) {
            for ((title, text) in topics) {
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = PtColors.WhiteKey)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
