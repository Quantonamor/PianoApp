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
- [x] CI: GitHub Actions (Core-Tests + APK-Build als Download-Artefakt)

### M2 – Core-Logik (lokal getestet)
- [x] Datenmodell (Song, Note, Hand, Abschnitte, Statistiken) + JSON
- [x] MIDI-Import (SMF Typ 0/1, Tempo, Taktart, Handzuordnung)
- [x] MusicXML-Import (.xml/.musicxml und komprimiert .mxl)
- [x] Demo-Stücke (Alle meine Entchen, Ode an die Freude, Für Elise-Anfang, Tonleiter)
- [x] Übungs-Engine (Warten/Durchlauf/Vorspielen, Schleifen, Tempo-Treppe, Hände, Wertung)
- [x] Tonerkennung (FFT, harmonische Salienz, Abgleich mit erwarteten Tönen, Anschlagserkennung)
- [x] OMR: Binarisierung, Notenlinien, Systeme, Notenköpfe, Hälse/Balken, Taktstriche, Rhythmus, Tonhöhe, Tonart

### M3 – Android-App
- [x] Bibliothek (Raster, Suche, Sortierung, Favoriten, Umbenennen, Löschen)
- [x] Import-Assistent (Datei wählen → Erkennung → Vorschau mit Overlay → Korrekturen → Speichern)
- [x] Übungsbildschirm (fallende Noten, Klaviatur, Zeitleiste mit Schleife, Steuerleiste)
- [x] Mikrofon-Anbindung + Berechtigung
- [x] Synthesizer (Vorspielen, Begleitung der anderen Hand, Metronom, Einzähler)
- [x] Einstellungen (Notennamen, Vorlaufzeit/Zoom, Mikro-Empfindlichkeit, Latenz)
- [x] Noten-Editor (Korrektur von Erkennungsfehlern)

### M4 – Feinschliff
- [x] Statistik je Stück (Bestwert, geübte Zeit, schwierige Takte)
- [x] App-Icon
- [x] Automatischer Bedien-Test auf Emulator in der CI (`app/src/androidTest/.../SmokeTest.kt`)
- [ ] **Test auf echtem Gerät durch den Nutzer** (Rückmeldung → Fehlerbehebung)
- [ ] Mikrofon-Erkennung mit echtem Klavier feinjustieren (Schwellen, Latenz)
- [ ] Performance: Notennamen-Texte zwischenspeichern, große Stücke flüssig zeichnen
- [ ] MusicXML: Wiederholungen ausklappen, Tempowechsel
- [ ] Originale PDF-Seite parallel zum Üben anzeigen (Taktmarkierung)
- [ ] Export/Backup der Bibliothek, Englische Übersetzung

### M5 – Noten-Editor 2.0 + „KI-nachbessern“ (Wunsch des Nutzers, **später angehen**)

Für bereits importierte Stücke, mit denen man nicht zufrieden ist. Zwei Wege, beide aus dem Stück-Menü
und aus dem Übungsbildschirm erreichbar („Noten korrigieren“):

**A) Editor (händisch), intuitiv & schön**
- [ ] Geteilte Ansicht: oben das Original-PDF (gespeichert unter `files/pdfs/<id>.pdf`) mit Takt-Markierung,
      unten der Piano-Roll / die Notenansicht des gleichen Takts – man sieht sofort, was abweicht.
- [ ] Takt-für-Takt-Navigation mit Kennzeichnung „verdächtiger“ Takte (Rhythmus geschätzt, Taktsumme falsch,
      ungewöhnliche Sprünge) – Liste „Bitte prüfen“.
- [ ] Direktes Bearbeiten: Note antippen → ziehen (Tonhöhe/Zeit), Griffe zum Verlängern, Akkord-Modus,
      Vorzeichen-Buttons (♯ ♭ ♮), Takt kopieren/einfügen, Rückgängig/Wiederholen, Anhören ab Takt.
- [ ] Takt-Werkzeuge: „Taktart/Tonart ab hier“, „Takt einfügen/löschen“, „Hand tauschen“.

**B) „KI-nachbessern“ (ein Knopf)**
- [ ] Einstellungen: API-Schlüssel eingeben (sicher gespeichert mit EncryptedSharedPreferences),
      Anbieter/Modell wählbar (Standard: Anthropic Claude, aktuelles Modell; Schlüssel bleibt auf dem Gerät).
- [ ] Ein Knopf „KI-nachbessern“: die App schickt pro Seite das **Bild der PDF-Seite** + die **erkannten Noten
      als kompakten Text** (Takt, Hand, Tonhöhe, Dauer) + die Warnungen der Erkennung an die KI.
- [ ] Fest hinterlegter, sorgfältig formulierter Prompt (Rolle „Notensatz-Korrektor“; Aufgabe: Abweichungen
      zwischen Bild und Notenliste finden; Ausgabe **nur** als strukturiertes JSON mit Korrekturen pro Takt
      `{measure, hand, notes:[{pitch, start, duration}]}` + kurzer Begründung; Taktsummen müssen aufgehen;
      Tonart/Taktart prüfen). JSON-Schema-Validierung, bei Fehler automatisch nachfragen.
- [ ] Fortschrittsanzeige: „Seite 2/4 wird geprüft … 3 Takte korrigiert“, Live-Protokoll was geändert wurde.
- [ ] Ergebnis als **Vorschlag** (Vorher/Nachher je Takt, farbig markiert), einzeln oder alles übernehmen,
      jederzeit rückgängig. Kosten-/Datenschutz-Hinweis vor dem ersten Senden.
- [ ] Technik: Ktor/OkHttp-Client in `app/ai/`, Prompt + JSON-Schema in `core` (testbar), Bilder verkleinert
      (z. B. 1600 px Breite, PNG/JPEG), Abbruch-Knopf, Wiederholung bei Netzwerkfehler.

### Konvergenz-Schleife der PDF-Erkennung (Wunsch des Nutzers, **läuft**)

Ziel: Zufallsrunden mit je 5 neuen Stücken (beide Notensatz-Programme), bis **5 Runden hintereinander alle
Stücke 100 %** (Noten, Präzision, Rhythmus) erreichen. Ablauf & Werkzeuge: `tools/omr-eval/README.md`.

Stand (2026-10-22):
- Runde 1 (Choräle BWV 4, 253, 370; Volkslieder Lancashire Clog, Salem Hornpipe): 1 von 10 PDFs bei 100 %,
  Runde-1-Schnitt 93 % Noten / 93 % Rhythmus. **Noch keine Runde vollständig bestanden – Zähler: 0/5.**
- Regressions-Satz (fester Korpus + Runde 1, 36 PDFs): **87,6 % Noten, 91,1 % Präzision, 89,5 % Rhythmus**
  (vorher 86/88/89).
- Bereits umgesetzt in dieser Schleife: Triolen (Ziffer „3“ + Taktsummen-Prüfung), verbundene
  Taktangaben-Ziffern, verschmolzene Kreuze in der Tonart, Hilfslinien-Ausnahme nur bei gedruckter Linie,
  Vorzeichen-Erkennung über ganze Zeichen, Fehlerarten-Statistik in der Auswertung.
- **Nächste Schritte (in dieser Reihenfolge):**
  1. ✅ Oktavierter Violinschlüssel (LilyPond erkannt, 57→73 %); bei Verovio wird die kleine 8 noch nicht gefunden.
  2. ✅ Gesangszeilen ohne Verbindung zum Klavier werden ignoriert; Klaviersysteme werden auch über
     geschweifte Klammer und durchgezogene Taktstriche erkannt, großer Abstand durch Liedtext erlaubt.
  2b. ✅ Tonart: Schlüssel-Auswahl ignoriert dünnen Systemstrich; obere/untere Zeilen stimmen getrennt ab.
  3. Choräle: zwei Stimmen pro Zeile (Sekunden-Akkorde, gemeinsame Köpfe), Vorzeichen in Akkorden.
  4. Tremolo-Schrägstrich (Salem Hornpipe) als Balken fehlinterpretiert.
  5. Lösung (`truth.py`) bei Auftakten musikalisch korrekt verschieben (music21 füllt Auftakte auf).
- Ehrliche Einschätzung: 100 % auf *jedem* Zufallsstück ist für Sonderfälle (Verzierungen, N-tolen > 3,
  Chorsätze) eventuell nicht erreichbar; dann Pool auf klaviertypische Literatur eingrenzen und dokumentieren.

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
