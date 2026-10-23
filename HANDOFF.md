# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-23 (Session 3)**, letzter Commit auf Branch `ccr-564d5418-uo5d3u` (setzt
`ccr-bbbc1057-0ev5g2` fort). Core-Tests lokal grün; CI nach jedem Push prüfen. Allgemeine Regeln in `CLAUDE.md`.

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
   bash tools/omr-eval/evalr.sh /tmp/omr-work/regress             # Stand: 98,7 / 98,7 / 99,4 % (126 PDFs)
   ```
   Runden 1–9 sind vollständig, Runde 10 zu 9/10 (offen: Jersey Lightning Jig LilyPond, fast senkrechter
   32tel-Balken – Details in `docs/PLAN.md`). Frisch zuletzt 5, 6, 3, 7, 6, 7 von 10. Nächste Runde:
   `source tools/omr-eval/env.sh; "$VPY" tools/omr-eval/round.py 11 <seed>` (Seeds bisher 4044 … 10101),
   **sofort ins Repo sichern** (Befehl im README) und neue Stücke in `/tmp/omr-work/regress` verlinken.
   Vor jedem Commit: volle Regression gegen den letzten Stand vergleichen (`diff` der OK/FAIL-Zeilen) – kleine
   Regeln haben oft Nebenwirkungen in anderen Stücken; einzelne Regeln testweise abschalten hilft beim Eingrenzen.
   Zähler „5 Runden hintereinander 100 %“: **0/5** (nur frische Runden zählen).
   Schnell einzelne Stücke prüfen: `mkdir -p /tmp/omr-work/one; ln -s <stückordner> /tmp/omr-work/one/;
   DIFF=1 bash tools/omr-eval/evalr.sh /tmp/omr-work/one -Pomr.trace=1`.
   Werkzeuge zur Fehlersuche: `-Pomr.trace=1` gibt u. a. `BLOB`, `HEAD`, `HOLE1`, `TIMESIG`, `ACCCAND`, `ARCCAND`,
   `BARS`, `SLOT` aus (Zeilen vor der ersten `TRUTH`-Zeile = LilyPond, danach Verovio);
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
