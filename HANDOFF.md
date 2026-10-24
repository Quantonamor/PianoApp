# Übergabe für die nächste Session („weiter“)

> **Neuester Stand (Session 2026-10-24):** Die aktuellste Arbeit liegt **nicht** auf diesem Branch, sondern auf
> `ccr-564d5418-uo5d3u` (Merge von `master` in den OMR-Konvergenz-Branch + OMR-Runde 11, Seed 11111).
> Dort sind die **letzten beiden CI-Läufe rot** (Runs 40017154811, 40017230383) → zuerst diesen Branch holen
> (`git fetch origin ccr-564d5418-uo5d3u` und in den Session-Branch mergen), CI-Logs ansehen
> (`get_job_logs`, „==== FAILED TESTS ====“) und reparieren. Das dortige HANDOFF.md ist maßgeblich.
> **APK-Download:** Es gibt kein GitHub-Release. Letzte grüne APK = Artefakt `PianoTiles-apk` in
> https://github.com/Quantonamor/PianoApp/actions/runs/40010092626 (Stand OMR-Runde 10, gültig bis 21.01.2027);
> `master`: https://github.com/Quantonamor/PianoApp/actions/runs/39940635093.
> Idee/Angebot an den Nutzer: Release-Workflow (APK automatisch als GitHub-Release mit festem Link).

Stand: **2026-10-23 (spät)**, letzter Commit auf Branch `ccr-257ad710-x5upqq`, **CI komplett grün**
(Core-Tests, APK-Build, Emulator-Bedientest). Allgemeine Regeln und Befehle stehen in `CLAUDE.md`.

## Was als Nächstes zu tun ist (Reihenfolge)

0. **Lokal mit Emulator:** jede UI-Änderung selbst ansehen (`tools/emu.sh install` + `shot`). Erster Emulator-Durchgang
   2026-10-23 erledigt (Bibliothek, Üben Warten/Fließend, Einstellungen, Hilfe, Editor, PDF-Import, PDF neben dem
   Üben, Schleifen-/Tempo-/Abschluss-Dialog, Stück-Menü, MIDI teilen + per Teilen importieren) – noch nicht
   angesehen: Backup/Wiederherstellen, KI-Panel (braucht API-Schlüssel). Idee aus dem Test: Metronomangabe (♩ = 100) im PDF lesen statt Standard 90 BPM.
1. **Rückmeldungen des Nutzers vom Gerätetest** haben immer Vorrang (Fehler beheben, CI grün halten, dokumentieren).
2. Sonst: **Konvergenz-Schleife der PDF-Erkennung** fortsetzen (`docs/PLAN.md` → „Konvergenz-Schleife“,
   Anleitung `tools/omr-eval/README.md`). Konkret:
   ```bash
   bash tools/omr-eval/setup.sh                                   # einmal pro Session
   bash tools/omr-eval/evalr.sh /tmp/omr-work/regress             # Stand: 94,2 / 94,3 / 96,4 %
   bash tools/omr-eval/evalr.sh /tmp/omr-work/rounds/r1           # Runde 1: 5 von 10 PDFs bei 100 %
   ```
   Läuft auch lokal unter Windows (Git Bash). Offene Punkte in Runde 1 (Reihenfolge, Details per
   `DIFF=1 bash tools/omr-eval/evalr.sh /tmp/omr-work/rounds/r1` – zeigt jetzt auch EXTRA/MISS mit Seitenposition):
   BWV 4 (Akkord-Sekunde 46/62@38 fehlt, Vorzeichen), BWV 370 (Rhythmus 96–97 %: Takt um Schlag 29–31,
   Oktavfehler beim ersten Akkord), Lancashire Clog Verovio (eine fehlende Note 76@54.5). Dann neue Runde:
   `source tools/omr-eval/env.sh; "$VPY" tools/omr-eval/round.py 2 <seed>` (läuft jetzt auch unter Windows),
   **Runde danach ins Repo sichern** (Befehl im README). Zähler „5 Runden hintereinander 100 %“: **0/5**.
   Choräle des Generators wurden korrigiert und neu gesetzt (Tonart in beiden Systemen, ♮ ausgeschrieben,
   Hals oben/unten je Stimme) – alte PDFs waren nicht lösungstreu.
   Werkzeuge zur Fehlersuche: `-Pomr.trace=1` gibt u. a. `BLOB`, `HEAD`, `TIMESIG`, `ACCCAND`, `SLOT` aus;
   Overlays in `/tmp/omr-work/omr/` (unter Windows `%LOCALAPPDATA%\Temp\omr-work\omr`).
3. Kleinere Ideen, falls Zeit bleibt: Verovio-PDFs mit sehr kleinen Notenköpfen, Hilfe-Text um Editor/KI ergänzen,
   Deprecation-Warnungen (`Icons.Filled.MenuBook` → AutoMirrored) aufräumen.

## Fertig (nicht neu anfangen)
- M1–M4 (Fundament, Core, App, Feinschliff) inkl. Backup/Restore, MIDI-Export, PDF neben dem Üben,
  MusicXML-Wiederholungen/Tempo, Performance.
- M5: **Editor 2.0** (`app/.../ui/editor/EditorScreen.kt`, `EditorViewModel.kt`, `core/edit/MeasureOps.kt`) und
  **KI-nachbessern** (`core/ai/AiPrompt.kt`, `core/ai/AiFixer.kt`, `app/.../ui/editor/AiPanel.kt`,
  Schlüssel in `app/.../data/SecretStore.kt`). Live mit echtem API-Schlüssel noch nie getestet – das macht der
  Nutzer; bei Fehlermeldungen von ihm dort ansetzen (SDK `com.anthropic:anthropic-java:2.34.0`,
  Modell `claude-opus-5-5`, strukturierte JSON-Ausgabe, `fallbacks: "default"`).
- Englische Oberfläche (`app/.../i18n/`, `core/i18n/CoreText.kt`), Umschalter in den Einstellungen.

## Was nur der Nutzer kann
- Test auf echtem Gerät (APK: GitHub Actions → letzter Lauf → Artefakt `PianoTiles-apk`).
- Mikrofon mit echtem Klavier feinjustieren (Schwellen in `core/audio/NoteDetector.kt`, Einstellungen).
- KI-nachbessern mit eigenem Anthropic-API-Schlüssel ausprobieren.

## Umgebung (wichtig)
- **Zwei mögliche Umgebungen:** (a) lokal am Windows-PC (VS Code) mit Android-SDK + Emulator → App selbst bauen,
  installieren und per Screenshot prüfen (`tools/emu.sh`, siehe CLAUDE.md); (b) Cloud-Container ohne SDK →
  App nur über die CI bauen; `./gradlew :core:test` läuft überall.
- GitHub nur über die MCP-Tools (`mcp__github__*`); Job-Logs: `get_job_logs` mit `tail_lines`.
  Laufstatus geht auch per `curl https://api.github.com/repos/Quantonamor/PianoApp/actions/runs?branch=<branch>`.
- Hilfsdateien der Auswertung liegen in `/tmp/omr-work` (geht mit dem Container verloren → `setup.sh` legt sie neu an).
