# Projektplan – PianoTiles Übungs-App

Dieses Dokument ist der "Fahrplan" des Projekts. Es wird bei jedem Arbeitsschritt
aktualisiert. Wenn du (der Nutzer) nur „weiter“ schreibst, wird der nächste offene
Punkt von oben nach unten abgearbeitet.

## Ziel

Eine Android-App (Tablet & Handy, Querformat), die beim Klavierüben hilft:

* Noten fallen wie bei „Piano Tiles“ / Synthesia von oben auf eine Klaviatur.
* **Mikrofon-Modus („Warten“)**: Die App hört zu und läuft erst weiter, wenn die richtigen
  Töne gespielt wurden.
* **Durchlauf-Modus**: Läuft in frei einstellbarer Geschwindigkeit einfach durch.
* **Vorspielen**: Die App spielt das Stück (oder eine Hand) selbst vor.
* **Übungsschleifen**: Beliebige Takte markieren und endlos/n‑mal wiederholen,
  optional mit „Tempo-Treppe“ (wird nach jedem fehlerfreien Durchgang schneller).
* **Eigene Bibliothek**: Import von **PDF-Noten** (automatische Notenerkennung / OMR),
  außerdem MIDI und MusicXML (.xml/.musicxml/.mxl) als verlässliche Alternative.

## Architektur-Entscheidungen (Kurzfassung, Details in `ARCHITECTURE.md`)

| Thema | Entscheidung | Begründung |
|---|---|---|
| Sprache/UI | Kotlin + Jetpack Compose | Moderner Android-Standard, gut für Canvas-Animationen |
| Modulaufteilung | `core` (reines Kotlin/JVM) + `app` (Android) | Logik (OMR, Tonerkennung, Übungs-Engine, Parser) ist ohne Android-SDK testbar |
| PDF → Noten | Eigene OMR-Pipeline in `core` | Läuft offline auf dem Gerät, keine Cloud, private Bibliothek bleibt privat |
| PDF-Rendering | `android.graphics.pdf.PdfRenderer` | Im System enthalten, keine Fremdbibliothek |
| Tonerkennung | FFT + harmonische Salienz (polyphon) | Erkennt auch Akkorde; im Warte-Modus wird gezielt nach erwarteten Tönen gesucht |
| Klangausgabe | Eigener kleiner Synthesizer (AudioTrack) | Keine Sound-Fonts nötig, APK bleibt klein |
| Speicherung | JSON-Dateien im App-Speicher (kotlinx.serialization) | Einfach, robust, exportierbar |
| Build | GitHub Actions baut die APK | In der Entwicklungsumgebung gibt es kein Android-SDK |

## Meilensteine

### M1 – Fundament
- [x] Projektgerüst (Gradle, Module `core` + `app`, Wrapper)
- [x] Plan & Doku-Struktur
- [ ] CI: GitHub Actions (Core-Tests + APK-Build als Download-Artefakt)

### M2 – Core-Logik (lokal getestet)
- [ ] Datenmodell (Song, Note, Hand, Abschnitte, Statistiken) + JSON
- [ ] MIDI-Import (SMF Typ 0/1, Tempo, Taktart, Handzuordnung)
- [ ] MusicXML-Import (.xml/.musicxml und komprimiert .mxl)
- [ ] Demo-Stücke (Alle meine Entchen, Ode an die Freude, Für Elise-Anfang, Tonleiter)
- [ ] Übungs-Engine (Warten/Durchlauf/Vorspielen, Schleifen, Tempo-Treppe, Hände, Wertung)
- [ ] Tonerkennung (FFT, harmonische Salienz, Abgleich mit erwarteten Tönen, Anschlagserkennung)
- [ ] OMR: Binarisierung, Notenlinien, Systeme, Notenköpfe, Hälse/Balken, Taktstriche, Rhythmus, Tonhöhe, Tonart

### M3 – Android-App
- [ ] Bibliothek (Raster, Suche, Sortierung, Favoriten, Umbenennen, Löschen)
- [ ] Import-Assistent (Datei wählen → Erkennung → Vorschau mit Overlay → Korrekturen → Speichern)
- [ ] Übungsbildschirm (fallende Noten, Klaviatur, Zeitleiste mit Schleife, Steuerleiste)
- [ ] Mikrofon-Anbindung + Berechtigung
- [ ] Synthesizer (Vorspielen, Begleitung der anderen Hand, Metronom, Einzähler)
- [ ] Einstellungen (Notennamen, Vorlaufzeit/Zoom, Mikro-Empfindlichkeit, Latenz)
- [ ] Noten-Editor (Korrektur von Erkennungsfehlern)

### M4 – Feinschliff
- [ ] Statistik je Stück (Bestwert, geübte Zeit, schwierige Takte)
- [ ] Englische Übersetzung
- [ ] App-Icon, Splash
- [ ] Tests auf echten Geräten (durch Nutzer) + Fehlerbehebung

## Bekannte Grenzen (ehrlich dokumentiert)

* **PDF-Notenerkennung** ist ein schwieriges Problem. Die eingebaute Erkennung ist für
  **sauber gedruckte/digital gesetzte Noten** (z. B. aus MuseScore, IMSLP-Neusatz) ausgelegt.
  Handschrift, schiefe Scans, sehr dichter Satz und Sonderzeichen werden nur teilweise
  erkannt. Deshalb gibt es nach dem Import eine Vorschau mit Korrekturmöglichkeit und den
  Noten-Editor. Wer verlässliche Ergebnisse braucht: MusicXML-Export (z. B. aus MuseScore
  oder Audiveris am PC) importieren.
* **Tonerkennung per Mikrofon** funktioniert am besten mit echtem/akustischem Klavier oder
  Digitalpiano über Lautsprecher in ruhiger Umgebung.

## Verlauf

Siehe `CHANGELOG.md`.
