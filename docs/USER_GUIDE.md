# Bedienungsanleitung

## Erste Schritte

1. App öffnen – in der **Bibliothek** liegen vier Demo-Stücke.
2. Ein Stück antippen → der **Übungsbildschirm** öffnet sich (Querformat).
3. Gerät aufs Notenpult stellen, auf ▶ tippen. Beim ersten Mal fragt die App nach dem Mikrofon.

## Der Übungsbildschirm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ ←  Titel / Takt 3 von 16 · 96 BPM  [Warten|Fließend|Anhören] [L|L+R|R] 80% ⏱ 🎤 ✋ ✎ │
├─────────────────────────────────────────────────────────────────────────────┤
│                       fallende Noten (blau = rechts, orange = links)          │
│ ── Taktlinien mit Nummern, violetter Bereich = Schleife                        │
│ ═══════════════════════ Trefferlinie ═══════════════════════════════════════ │
│                     Klaviatur mit den Händen darüber                         │
├─────────────────────────────────────────────────────────────────────────────┤
│ ↺  ⏪  ▶  ⏩   [▁▃▅▂▇▃ Zeitleiste ▁▂▅▃]   🔁  T. 5–8                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

* **Warten**: Die Noten halten an der Linie, bis du die richtigen Töne spielst. Unten steht, was zu spielen ist.
* **Fließend**: Läuft im gewählten Tempo; mit Mikrofon wird die Trefferquote angezeigt.
* **Anhören**: Die App spielt vor.
* **L / L+R / R**: Welche Hand du übst. Die andere Hand wird blasser dargestellt und mitgespielt.
* **Tempo** (z. B. „80 %“): antippen zum Einstellen.
* **⏱**: Metronom. **🎤**: Mikrofon an/aus (grüner Balken = Pegel). **✎**: Noten bearbeiten.
* **✋ Hände**: blendet zwei gezeichnete Hände über der Klaviatur ein bzw. aus. Sie gleiten mit dem Stück mit; der
  Finger, der gerade spielt, drückt seine Taste und leuchtet auf. Die Zahlen auf den Fingernägeln und in den
  fallenden Noten sind der Fingersatz (1 = Daumen … 5 = kleiner Finger). Hat ein Stück keinen Fingersatz, rechnet
  die App beim Öffnen automatisch einen aus (Tonleiter-Untersatz, Fünffinger-Lagen, Akkorde, möglichst kein Daumen
  auf schwarzen Tasten); Fingersätze aus MusicXML-/MuseScore-Dateien bleiben erhalten. Fingerzahlen und Hände lassen
  sich auch in den Einstellungen getrennt abschalten.

### Gesten

| Geste | Wirkung |
|---|---|
| Tippen auf die Noten | Start / Pause |
| Senkrecht wischen | Vor- / Zurückspulen |
| Doppeltippen | aktuellen Takt als Schleife |
| Über die Zeitleiste ziehen | Takte für die Schleife markieren |
| Zeitleiste antippen | zu diesem Takt springen |
| Klaviertasten antippen | Ton spielen (zählt auch als Eingabe) |

### Schleifen & Tempo-Treppe

Über den 🔁-Knopf schaltest du die Schleife an/aus, über das Feld daneben („T. 5–8“) öffnest du die Optionen:
Bereich, Anzahl Wiederholungen (∞, 3×, 5×, 10×), **Tempo-Treppe** (startet langsamer und wird nach
jedem Durchgang mit ≥ 90 % Treffern um 5 % schneller bis zum Zieltempo), Abschnitte speichern
(z. B. „Refrain“) und **schwierige Takte** – die App merkt sich, wo du die meisten Fehler machst
(rot in der Zeitleiste).

## Noten importieren

„Noten importieren“ in der Bibliothek → Datei wählen (PDF, MIDI, MusicXML). Man kann Dateien auch
aus anderen Apps mit „Öffnen mit“ / „Teilen“ an PianoTiles schicken.

**PDF**: Die Seiten werden analysiert, erkannte Noten werden farbig eingerahmt. Rechts kannst du
Titel, Tempo, Notensystem, Tonart und Taktart korrigieren und neu erkennen lassen.
„Speichern & üben“ oder „Speichern & korrigieren“ (öffnet den Editor).

## Noten-Editor

Erreichbar über das Stift-Symbol beim Üben oder im Stück-Menü der Bibliothek.

* **Aufbau**: oben das Original-PDF (bei importierten PDFs) mit dem aktuellen Takt blau markiert, verdächtige
  Takte orange; unten der Piano-Roll desselben Takts. Antippen eines Takts im PDF springt dorthin.
* **Takt-Leiste**: ‹ Takt n / N ›, **„Bitte prüfen (k)“** listet auffällige Takte (Rhythmus geschätzt, Takt
  nicht gefüllt, Noten über dem Taktstrich, sehr große Sprünge), „Nächster prüfen ›“ springt weiter.
  ▶ spielt zwei Takte ab dem aktuellen vor. **Takt-Werkzeuge**: kopieren/einfügen, leeren Takt davor
  einfügen, Takt löschen/leeren, Hände tauschen, ½ Ton höher/tiefer, Taktart ändern, ganzes Stück
  transponieren, am Raster ausrichten. Raster 1/16, 1/8, 1/4 oder Triole; „Hinzufügen“ + Hand.
* **Noten bearbeiten**: antippen = auswählen; **ziehen** = verschieben (hoch/runter = Tonhöhe,
  links/rechts = Zeit, am rechten Rand ziehen = Länge); freie Fläche ziehen = blättern. Unten: ♯ ♭,
  Oktave, früher/später, kürzer/länger, Notenwert direkt (1/16 … 1/1), Hand, anhören, löschen.
* **Rückgängig / Wiederholen** oben, „Speichern“ sichert das Stück.

### KI-nachbessern

1. Einmalig in den **Einstellungen → KI-nachbessern** einen eigenen API-Schlüssel von Anthropic eintragen
   (console.anthropic.com → API Keys). Er wird verschlüsselt nur auf dem Gerät gespeichert.
2. Im Editor **„KI-nachbessern“** tippen. Beim ersten Mal erscheint ein Hinweis, was gesendet wird (Bild jeder
   Notenseite + erkannte Noten) – die Kosten laufen über deinen Schlüssel.
3. Rechts erscheint das KI-Fenster: Fortschritt („Seite 2/4 wird geprüft …“), Protokoll und die **Vorschläge**
   je Takt und Hand mit Begründung, „Vorher“ und „Nachher“. „Ansehen“ zeigt den Vorschlag grün gestrichelt im
   Piano-Roll. **Übernehmen**, **Verwerfen** oder **Alle übernehmen** – alles lässt sich mit Rückgängig zurücknehmen.
4. Danach „Speichern“.

## Tipps fürs Mikrofon

* Ruhige Umgebung, Gerät nah am Klavier.
* Digitalpiano: über Lautsprecher spielen (nicht Kopfhörer).
* Werden Töne nicht erkannt → Empfindlichkeit erhöhen; reagiert es auf Geräusche → senken.
