# Architektur

## Module

```
┌──────────────────────────── app (Android, Compose) ────────────────────────────┐
│ MainActivity ── Navigation (Back-Stack) ── ScreenScope (ViewModel je Bildschirm) │
│  ├─ LibraryScreen          Bibliothek, Suche, Sortierung, Favoriten             │
│  ├─ PracticeScreen + VM    FallingNotes · Keyboard · Timeline · Dialoge          │
│  ├─ ImportScreen + VM      PdfRenderer → OMR → Vorschau mit Overlay → Speichern  │
│  ├─ EditorScreen + VM      PDF + Piano-Roll, Prüfliste, Takt-Werkzeuge, AiPanel   │
│  └─ Settings/Help                                                               │
│ data/  SongRepository (JSON, ZIP-Backup), SettingsStore, SecretStore (Keystore)  │
│ audio/ Synth (AudioTrack), MicInput (AudioRecord → NoteDetector)                 │
└─────────────────────────────────────┬──────────────────────────────────────────┘
                                      │ benutzt
┌─────────────────────────── core (reines Kotlin/JVM) ───────────────────────────┐
│ model/    Song, Note, Hand, Section, SongStats, Pitch, DemoSongs, SongUtils      │
│ io/       MidiImporter, MusicXmlImporter                                         │
│ omr/      GrayImage/BitImage, StaffDetector, OmrEngine                           │
│ audio/    Fft, NoteDetector                                                      │
│ practice/ PracticeEngine                                                         │
│ edit/     MeasureOps (Takt-Werkzeuge, Prüfliste)                                 │
│ ai/       AiPrompt (Prompt, JSON-Schema, Parser), AiFixer (Claude über Anthropic-SDK) │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Warum ein separates `core`-Modul?** Alle schwierigen Teile (Notenerkennung, Tonerkennung,
Übungslogik, Parser) sind plattformunabhängig und werden mit JUnit getestet – auch ohne
Android-SDK. Die App ist nur die Oberfläche darüber.

## Zeitmodell

Alle musikalischen Zeiten sind in **Schlägen (Viertelnoten)** gespeichert, unabhängig vom Tempo.
Sekunden ergeben sich aus `bpm × Tempo-Faktor`. Dadurch ändern Tempoänderungen nie die Noten.

## PracticeEngine

* Gruppiert die Noten der geübten Hand(en) zu **Schritten** (gleichzeitig beginnende Noten = Akkord).
* `update(dt)` schiebt die Position weiter:
  * **WAIT**: nie über den nächsten unerledigten Schritt hinaus. Ein Schritt ist erledigt, wenn alle
    seine Töne gehört wurden (Anschläge oder klingende Töne; wiederholte Töne müssen neu angeschlagen werden).
  * **FLOW**: Schritte, deren Zeitfenster (±0,3 s) verstreicht, gelten als verpasst.
  * **LISTEN**: alle Noten werden als `PlayNote`-Ereignisse ausgegeben.
* Schleife: Bereich `[start, end)`, Vorlauf ½ Takt, Wiederholungen, Tempo-Treppe.
* Ereignisse (`PlayNote`, `Click`, `PassCompleted`, `SongFinished`, `StepHit`, `WrongNote`) werden
  von der App in Klang und Anzeige umgesetzt.

## Tonerkennung (NoteDetector)

1. 8192-Punkte-FFT (Hann-Fenster) alle 2048 Samples (≈ 46 ms) bei 44,1 kHz.
2. Für jede der 88 Tasten: gewichtete Summe der Spektralspitzen an den ersten 8 Obertönen (Salienz).
3. Stärkste Taste wählen, deren Obertöne aus dem Spektrum entfernen, wiederholen (bis 6 Töne) → polyphon.
4. Rauschschwelle = Median des Spektrums (passt sich der Umgebung an), RMS-Gate.
5. Anschlag = Taste neu oder Salienz deutlich gestiegen (erkennt wiederholte Töne).

Töne, die der eigene Synthesizer gerade spielt, ignoriert die App (sonst „hört“ sie sich selbst).

## Speicherung

* `files/songs/<id>.json` – ein Stück (kotlinx.serialization), atomar über Temp-Datei geschrieben.
* `files/pdfs/<id>.pdf` – Original-PDF importierter Stücke.
* `SharedPreferences "settings"` – Einstellungen.

## Build & CI

`settings.gradle.kts` bindet `:app` nur ein, wenn ein Android-SDK vorhanden ist. GitHub Actions
(`.github/workflows/android.yml`) führt die Core-Tests aus und baut Debug- und Release-APK
(Release mit Debug-Schlüssel signiert, damit direkt installierbar) als Download-Artefakt.

## Hände & Fingersatz

* `core/hands/AutoFingering.kt`: Fingersatz je Hand per dynamischer Programmierung (Viterbi) über Akkord-Schritte;
  Kandidaten = alle Finger-Zuordnungen, die mit der Tonhöhe steigen (links gespiegelt). Kosten nach Parncutt et al.
  (1997): bequeme/mögliche Spannweite je Fingerpaar (im Akkord streng, nacheinander etwas großzügiger),
  Daumen-Untersatz/Übersatz (mit 3/4 leicht, mit 2/5 teuer), Daumen/kleiner Finger auf schwarzen Tasten, gleicher
  Finger auf anderer Taste. `ensure()` rechnet automatische Fingersätze nach Änderungen neu, Fingersätze aus Datei,
  KI oder von Hand bleiben und nur Lücken werden gefüllt. Tests: `HandsTest`.
* `core/hands/HandMotion.kt`: Handhaltung über die Zeit (Fingerpositionen in Weißtasten-Einheiten `KeyX`,
  freie Finger mit natürlichem Abstand, Gleiten zur nächsten Lage im letzten Teil der Lücke, Tastendruck 0..1).
* App: `ui/practice/HandsOverlay.kt` zeichnet die Hände (Handfläche vom unteren Rand, schattierte, perspektivisch
  verjüngte Finger, Fingernägel mit Zahl, Schatten auf den Tasten) über der Klaviatur; Fingerzahl auf den fallenden
  Noten in `FallingNotes.kt`.

## KI-nachbessern

* `core/ai/AiPrompt.kt`: fester System-Prompt (Rolle Notensatz-Korrektor), Seitentext je PDF-Seite
  (Takte mit Position auf der Seite in %, Noten je Hand als `Tonhöhe@Start/Dauer`), JSON-Schema der Antwort,
  Parser mit Prüfung (Takt auf der Seite? Tonhöhe gültig? Start/Dauer im Takt?).
* `core/ai/AiFixer.kt`: `ClaudeReviewer` ruft die Messages-API über das offizielle Java-SDK auf
  (`claude-opus-5-5`, adaptives Denken mit Aufwand `high`, `output_config.format` = JSON-Schema,
  `fallbacks: "default"` mit Beta-Header `server-side-fallback-2026-07-01`, Abbruch bei `refusal`/`max_tokens`).
  `AiFixRunner` arbeitet Seite für Seite, meldet Fortschritt/Protokoll und fragt bei ungültiger Antwort einmal
  mit der Fehlerliste nach.
* `app/ui/editor/EditorViewModel.kt` rendert die PDF-Seiten (1600 px, JPEG), startet den Lauf im Hintergrund
  und verwaltet die Vorschläge; Übernehmen ist ein normaler Editor-Schritt (Rückgängig möglich).
