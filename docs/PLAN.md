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
- [~] Test auf dem lokalen Emulator (2026-10-23: Bibliothek, Üben, Einstellungen, Hilfe, Editor, PDF-Import,
      PDF neben dem Üben, Dialoge, Teilen geprüft, 9 Fehler behoben; offen: Backup, KI-Panel)
- [ ] Idee: Metronomangabe (♩ = 100) aus dem PDF lesen (derzeit Standard 90 BPM)
- [ ] Mikrofon-Erkennung mit echtem Klavier feinjustieren (Schwellen, Latenz)
- [x] Performance: Notennamen-Texte zwischenspeichern (Text-Cache 512), große Stücke flüssig zeichnen
- [x] MusicXML: Wiederholungen + Volta-Klammern ausklappen, Tempowechsel (MusicXML + MIDI, Tempo-Karte in der Engine)
- [x] Originale PDF-Seite parallel zum Üben anzeigen (Taktmarkierung, Antippen springt zum Takt)
- [x] Backup/Wiederherstellen der Bibliothek (ZIP), Export als MIDI (Teilen)
- [x] Englische Übersetzung (automatisch nach Gerätesprache, umschaltbar in den Einstellungen; KI-Begründungen in der UI-Sprache)

### M5 – Noten-Editor 2.0 + „KI-nachbessern“ (Wunsch des Nutzers) – **umgesetzt**, wartet auf Test am Gerät

Für bereits importierte Stücke, mit denen man nicht zufrieden ist. Zwei Wege, beide aus dem Stück-Menü
und aus dem Übungsbildschirm erreichbar („Noten korrigieren“):

**A) Editor (händisch), intuitiv & schön**
- [x] Geteilte Ansicht: oben das Original-PDF (gespeichert unter `files/pdfs/<id>.pdf`) mit Takt-Markierung,
      unten der Piano-Roll / die Notenansicht des gleichen Takts – man sieht sofort, was abweicht.
- [x] Takt-für-Takt-Navigation mit Kennzeichnung „verdächtiger“ Takte (Rhythmus geschätzt, Taktsumme falsch,
      ungewöhnliche Sprünge) – Liste „Bitte prüfen“.
- [x] Direktes Bearbeiten: Note antippen → ziehen (Tonhöhe/Zeit), Griffe zum Verlängern, Akkord-Modus,
      Vorzeichen-Buttons (♯ ♭ ♮), Takt kopieren/einfügen, Rückgängig/Wiederholen, Anhören ab Takt.
- [x] Takt-Werkzeuge: „Taktart/Tonart ab hier“, „Takt einfügen/löschen“, „Hand tauschen“.

**B) „KI-nachbessern“ (ein Knopf)**
- [x] Einstellungen: API-Schlüssel eingeben (sicher gespeichert mit EncryptedSharedPreferences),
      Anbieter/Modell wählbar (Standard: Anthropic Claude, aktuelles Modell; Schlüssel bleibt auf dem Gerät).
- [x] Ein Knopf „KI-nachbessern“: die App schickt pro Seite das **Bild der PDF-Seite** + die **erkannten Noten
      als kompakten Text** (Takt, Hand, Tonhöhe, Dauer) + die Warnungen der Erkennung an die KI.
- [x] Fest hinterlegter, sorgfältig formulierter Prompt (Rolle „Notensatz-Korrektor“; Aufgabe: Abweichungen
      zwischen Bild und Notenliste finden; Ausgabe **nur** als strukturiertes JSON mit Korrekturen pro Takt
      `{measure, hand, notes:[{pitch, start, duration}]}` + kurzer Begründung; Taktsummen müssen aufgehen;
      Tonart/Taktart prüfen). JSON-Schema-Validierung, bei Fehler automatisch nachfragen.
- [x] Fortschrittsanzeige: „Seite 2/4 wird geprüft … 3 Takte korrigiert“, Live-Protokoll was geändert wurde.
- [x] Ergebnis als **Vorschlag** (Vorher/Nachher je Takt, farbig markiert), einzeln oder alles übernehmen,
      jederzeit rückgängig. Kosten-/Datenschutz-Hinweis vor dem ersten Senden.
- [x] Technik: Ktor/OkHttp-Client in `app/ai/`, Prompt + JSON-Schema in `core` (testbar), Bilder verkleinert
      (z. B. 1600 px Breite, PNG/JPEG), Abbruch-Knopf, Wiederholung bei Netzwerkfehler.

  Umsetzung (2026-10-22): offizielles Anthropic-Java-SDK (`com.anthropic:anthropic-java`) in `core/ai/AiFixer.kt`
  (Modell `claude-opus-5-5`, Aufwand `high`, strukturierte Ausgabe per JSON-Schema, Server-Fallback bei Ablehnung,
  3 automatische Wiederholungen bei Netzwerk/Überlast), Prompt/Schema/Parser in `core/ai/AiPrompt.kt` (getestet in
  `AiAndEditTest`), Schlüssel per Android-Keystore (AES-GCM) in `app/data/SecretStore.kt` statt der veralteten
  EncryptedSharedPreferences. Der Editor zeigt Vorschläge als grün gestrichelte Vorschau im Piano-Roll.
  Noch offen: echter Test mit API-Schlüssel (nur durch den Nutzer möglich).

### Konvergenz-Schleife der PDF-Erkennung (Wunsch des Nutzers, **läuft**)

Ziel: Zufallsrunden mit je 5 neuen Stücken (beide Notensatz-Programme), bis **5 Runden hintereinander alle
Stücke 100 %** (Noten, Präzision, Rhythmus) erreichen. Ablauf & Werkzeuge: `tools/omr-eval/README.md`.

Stand (2026-10-23, Session 3):
- Runde 4 durch Korrekturen der Parallel-Session 10/10. **Frische Runden:** 5 (Seed 5055) 5/10, 6 (Seed 6066)
  6/10, 7 (Seed 7077) 3/10 – jeweils danach 10/10. **Zähler „5 Runden hintereinander 100 %“: 0/5.**
- Regressions-Satz (fester Korpus + Runden 1–7, 96 PDFs): **98,2 % Noten, 98,2 % Präzision, 99,1 % Rhythmus**.
- Umgesetzt: Triole im Auftakt (bezifferte „3“ mittig über drei gleichen Balkennoten; unbeziffert, wenn der
  Partnertakt – Schlusstakt bzw. Ende des vorigen Abschnitts – die Länge verlangt), unvollständiger Takt vor
  Wiederholungs-/Doppelstrich ergänzt den Auftakt (Prima Volta), Triolen-Ziffer über die Halsposition zuordnen,
  unbezifferte Triolen auch bei einer Stimme mit gewendeten Hälsen (an der Mittellinie), Balkenstück am Halsende
  ist kein Kopf, Lochhälfte eines hohlen Kopfes auf Hilfslinie (zwei Hälse), sehr kurze Haltebögen zwischen
  gleichen Köpfen, Haltebogen ab Notenlinie bis 2,3 Abstände nach dem Kopf, Klaviersysteme bis 28 Abstände
  (vier Strophen), Zelle zwischen ♯ und Kopf (versetzte Striche, überstehende Balken), Zelle unter einem Kopf
  am Hals (Vorschlagsbogen) kein Loch, ♭ mit 2-px-Hals, ♭ am Balken (Formsuche vor Köpfen ohne Vorzeichen),
  Fähnchen über Systemgrenzen gehört dem linken Hals, Liedtext unter dem Halsende ist kein Balken.
- Generator: Vorzeichen für alle Stücke von Hand gesetzt (Verovio ließ ♯ nach ♮ im selben Takt weg);
  `setup.sh` hält gleichnamige Choräle verschiedener Runden auseinander (`chorale_bwv245_r7`).

Stand (2026-10-23, spät, Session 2):
- **Runden 1–3 je 10/10 PDFs bei 100 %** (nach Korrekturen; zählen nicht für den Zähler). Runde 4 (Seed 4044)
  frisch: 3/10 bei 100 %, nach Korrekturen 5/10 (offen: BWV 336/388 Verovio Präzision 98,7/99,5 %, BWV 392
  Vorzeichen in beiden + Verovio-Rhythmus 96 % (Kopf berührt Taktstrich → Taktstrich fehlt), Molly McGuire's Reel
  LilyPond 98,3 % (16tel-Fähnchen im Auftakt als Achtel gelesen)). **Zähler „5 Runden hintereinander 100 %“: 0/5.**
- Regressions-Satz (fester Korpus + Runde 1, 36 PDFs): **94,9 % Noten, 94,9 % Präzision, 97,6 % Rhythmus**
  (Sessionbeginn 94,2/94,3/96,4).
- Generator korrigiert: Vorzeichen in Chorälen über beide Stimmen eines Systems (Verovio ließ ♮ weg); alle
  Runden-Choräle neu gesetzt. `rescore.py` findet den richtigen Choral (bwv244 → bwv244.15 über `used_pieces.txt`).
- Session 2 umgesetzt: ♭-Form nur aus eigenen Pixeln (♮ daneben), hohle Note mit zwei Hälsen (beide Stimmen),
  Balkensuche schneidet ♮-Strich nicht mehr an, Haltebogen-Stücke am Taktstrich vor der Formprüfung verbinden,
  Stimmen-Trennung auch in unvollständigen Takten, Viertel+Hals ≠ verbundene Taktangabe, Haltebogen zu
  verschobenem Sekundkopf / entlang einer Notenlinie, Vorzeichen durch Haltebogen ausgeschnitten, Zelle zwischen
  ♯/♮ und Kopf kein Loch, Taktart zählt jede Systemsumme, Fermate am Halsende ist kein Balken, Tonart-♯ nicht als
  Vorzeichen der ersten Note, gebrochene 6/8-Ziffer, Einklang/Sekunde zweier Stimmen = eine Spalte,
  winzige Zelle Kopf/Hals/Linie kein Loch; 8tel/16tel-Pausen über ihre Form, unbezifferte Tuolen, Aufstrich-„V“
  ≠ Triolen-3, Wiederholungspunkte ≠ Verlängerungspunkt, Abschnittsende + Auftakt, letzter Takt ergänzt den
  Auftakt, Fermaten-Takte (3+1) auch wenn ein System zufällig 4 Schläge ergibt, Stimmen der Ober-/Unterstimme
  mit verschobenen Pausen, leere Takte zwischen Doppelstrichen, ♭ der Tonart ≠ Taktangabe.
- 2026-10-23 umgesetzt: Auftakt in den Lösungen (✅ alter Punkt 5), unvollständige Takte, ♮/♯-Fenster nicht als
  Notenloch füllen, Balken vs. Nachbarkopf, gemeinsamer Hals bei halben Noten, Vorzeichen-Zuordnung,
  Verovio-„C“, Akkord ≠ Taktangabe; danach: Balkenlücke ≠ hohler Kopf, Rhythmus je Stimme (Halsrichtung),
  Haltebögen über Taktstrich/Zeilenumbruch, kurze letzte Notenzeile, Choral-Generator korrigiert (neu gesetzt).
- Bereits umgesetzt in dieser Schleife: Triolen (Ziffer „3“ + Taktsummen-Prüfung), verbundene
  Taktangaben-Ziffern, verschmolzene Kreuze in der Tonart, Hilfslinien-Ausnahme nur bei gedruckter Linie,
  Vorzeichen-Erkennung über ganze Zeichen, Fehlerarten-Statistik in der Auswertung.
- **Nächste Schritte (in dieser Reihenfolge):**
  1. ✅ Oktavierter Violinschlüssel (LilyPond erkannt, 57→73 %); bei Verovio wird die kleine 8 noch nicht gefunden.
  2. ✅ Gesangszeilen ohne Verbindung zum Klavier werden ignoriert; Klaviersysteme werden auch über
     geschweifte Klammer und durchgezogene Taktstriche erkannt, großer Abstand durch Liedtext erlaubt.
  2b. ✅ Tonart: Schlüssel-Auswahl ignoriert dünnen Systemstrich; obere/untere Zeilen stimmen getrennt ab.
  3. Choräle: zwei Stimmen pro Zeile – teilweise erledigt; offen: Sekunden/gemeinsame Köpfe (fehlende
     Akkordtöne, v. a. Verovio), gefüllte Köpfe fälschlich „hohl“ in gestapelten Akkorden, Vorzeichen in Akkorden.
  4. Tremolo-Schrägstrich (Salem Hornpipe) als Balken fehlinterpretiert (LilyPond jetzt 100 %, Verovio 99 %).
  5. ✅ Lösung bei Auftakten korrigiert (`truthlib.py`, neu erzeugen mit `rescore.py`).
  6. ✅ Runden-Erzeugung läuft unter Windows (pypdf statt pdfunite, LilyPonds eigenes python.exe).
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
