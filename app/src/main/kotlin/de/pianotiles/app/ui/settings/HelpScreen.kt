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
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors

/** Help topics as (title, text); translated when shown. */
private val topics = listOf(
    "Die drei Modi" to
        "• Warten: Die Noten bleiben an der Linie stehen, bis du die richtigen Tasten spielst. Ideal zum Lernen – du bestimmst das Tempo.\n• Fließend: Die Noten laufen im eingestellten Tempo durch. Mit Mikrofon wird mitgezählt, wie viele du triffst.\n• Anhören: Die App spielt das Stück vor.",
    "Hände getrennt üben" to
        "Wähle oben L (links), R (rechts) oder L+R. Die andere Hand wird leiser angezeigt und – wenn eingeschaltet – automatisch mitgespielt.",
    "Notenblatt" to
        "Das Buch-Symbol oben zeigt die Noten neben den fallenden Noten; der aktuelle Takt ist markiert, Antippen springt dorthin. Bei PDFs und Fotos siehst du das Original, bei allen anderen Stücken setzt die App das Notenblatt selbst aus den Noten (mit Fingersatz) und hält es nach Änderungen im Editor aktuell.",
    "Akkorde & Halten" to
        "Akkorde zählen nur, wenn ihre Tasten zusammen angeschlagen oder gemeinsam gehalten werden. Musst du eine Taste loslassen, während eine andere weiterklingt, wird sie weiß mit Pfeil nach oben markiert („Loslassen“). Lässt du eine Note zu früh los, leuchtet ihre Taste grün umrandet („Halten“) – das geht nur mit den Bildschirm-Tasten, weil das Mikrofon den Nachklang hört.",
    "Hände & Fingersatz" to
        "Über das Auge (Menü „Ansicht“) blendest du Hände über der Klaviatur und den Fingersatz ein: Die Hände gleiten mit dem Stück mit, der spielende Finger drückt seine Taste; die Zahlen (1 = Daumen … 5 = kleiner Finger) stehen auch in den fallenden Noten. Hat ein Stück keinen Fingersatz, rechnet die App automatisch einen aus; Fingersätze aus MusicXML- oder MuseScore-Dateien bleiben erhalten.",
    "Teile wiederholen (Schleife)" to
        "• Ziehe mit dem Finger über die Zeitleiste unten, um Takte zu markieren – die Schleife startet sofort.\n• Doppeltippe auf die fallenden Noten, um den aktuellen Takt zu wiederholen.\n• Über den Schleifen-Knopf: Anzahl Wiederholungen, Tempo-Treppe (wird nach jedem guten Durchgang schneller), Abschnitte speichern und schwierige Takte direkt üben.",
    "Steuerung" to
        "Tippen auf die Noten = Start/Pause. Senkrecht wischen = vor- und zurückspulen. ⏪/⏩ springen taktweise. Die Tasten unten kann man auch antippen – praktisch ohne Klavier.",
    "Mikrofon" to
        "Lege das Gerät auf das Notenpult. In ruhiger Umgebung funktioniert die Erkennung am besten. In den Einstellungen zeigt „Mikrofon testen“ live, welche Töne die App hört – dort stellst du auch die Empfindlichkeit ein.",
    "MIDI-Keyboard" to
        "Hat dein Digitalpiano oder Keyboard USB- oder Bluetooth-MIDI, verbinde es mit dem Gerät (USB-Kabel mit Adapter bzw. in den Bluetooth-Einstellungen koppeln). Die App erkennt es automatisch (Klavier-Symbol oben statt Mikrofon) – jede Taste wird exakt und ohne Verzögerung erkannt, auch Akkorde und das Loslassen.",
    "PDF importieren" to
        "Tippe in der Bibliothek auf „Noten importieren“ und wähle ein PDF. Die App erkennt Notenlinien, Noten, Vorzeichen, Pausen und Rhythmus und zeigt das Ergebnis farbig über dem Notenblatt an. Tonart, Taktart und Notensystem kannst du dort korrigieren und neu erkennen lassen. Kleine Fehler behebst du im Noten-Editor. Am zuverlässigsten: digital gesetzte Noten. Alternativ MusicXML (.musicxml/.mxl) oder MIDI importieren.",
    "Noten fotografieren" to
        "„Noten importieren“ → „Noten fotografieren“: Nimm eine Seite nach der anderen auf (oder wähle Fotos aus der Galerie) und bringe sie mit ◀ ▶ in die richtige Reihenfolge. Die App sucht das Blatt, gleicht Licht und Schatten aus und dreht es gerade. Am besten: Blatt flach hinlegen, von oben, helles gleichmäßiges Licht.",
    "Weitere Formate" to
        "Außer PDF und Fotos kannst du MIDI (.mid/.kar), MusicXML (.musicxml/.xml/.mxl), ABC (.abc), MuseScore (.mscz/.mscx) und MEI (.mei) importieren – diese Dateien werden ohne Erkennung exakt übernommen.",
    "Bibliothek, Abspielen & Teilen" to
        "▶ auf einer Karte spielt das Stück sofort vor. Lange drücken wählt mehrere Stücke aus (teilen oder löschen). Über ⋮ teilst du ein Stück als Paket (.pianotiles) – darin sind Noten (MusicXML), Original, Übungsdaten und Fingersatz; andere öffnen es einfach mit PianoTiles. Außerdem als MusicXML oder MIDI für andere Programme.",
    "Lizenzen" to
        "Klavierklang: Salamander Grand Piano V3 von Alexander Holm (CC BY 3.0), über die npm-Pakete @audio-samples/piano-mp3 (MIT, Jan Forst); gekürzt und neu kodiert.\nHand-Modell: WebXR Input Profiles „generic-hand“ (MIT, © 2019 Amazon).\nNotensymbole: Bravura (SIL Open Font License).",
    "Noten-Editor" to
        "Tippe eine Note an und ändere Tonhöhe, Position, Länge oder Hand. Im Modus „Hinzufügen“ setzt ein Tipp auf eine freie Stelle eine neue Note. Rückgängig ist jederzeit möglich.",
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
            Text(tr("Hilfe"), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 820.dp)) {
            for ((title, text) in topics) {
                Spacer(Modifier.height(16.dp))
                Text(tr(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(tr(text), style = MaterialTheme.typography.bodyMedium, color = PtColors.WhiteKey)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
