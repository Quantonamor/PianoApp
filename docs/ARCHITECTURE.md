# Architektur

## Module

```
┌──────────────────────────── app (Android, Compose) ────────────────────────────┐
│ MainActivity ── Navigation (Back-Stack) ── ScreenScope (ViewModel je Bildschirm) │
│  ├─ LibraryScreen          Bibliothek, Suche, Sortierung, Favoriten             │
│  ├─ PracticeScreen + VM    FallingNotes · Keyboard · Timeline · Dialoge          │
│  ├─ ImportScreen + VM      PdfRenderer → OMR → Vorschau mit Overlay → Speichern  │
│  ├─ EditorScreen           Piano-Roll                                           │
│  └─ Settings/Help                                                               │
│ data/  SongRepository (JSON-Dateien), SettingsStore (SharedPreferences)          │
│ audio/ Synth (AudioTrack), MicInput (AudioRecord → NoteDetector)                 │
└─────────────────────────────────────┬──────────────────────────────────────────┘
                                      │ benutzt
┌─────────────────────────── core (reines Kotlin/JVM) ───────────────────────────┐
│ model/    Song, Note, Hand, Section, SongStats, Pitch, DemoSongs, SongUtils      │
│ io/       MidiImporter, MusicXmlImporter                                         │
│ omr/      GrayImage/BitImage, StaffDetector, OmrEngine                           │
│ audio/    Fft, NoteDetector                                                      │
│ practice/ PracticeEngine                                                         │
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
