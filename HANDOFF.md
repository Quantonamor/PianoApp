# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-22**, letzter Commit auf Branch `ccr-257ad710-x5upqq`, **CI komplett grün**
(Core-Tests, APK-Build, Emulator-Bedientest). Allgemeine Regeln und Befehle stehen in `CLAUDE.md`.

## Was als Nächstes zu tun ist (Reihenfolge)

1. **Rückmeldungen des Nutzers vom Gerätetest** haben immer Vorrang (Fehler beheben, CI grün halten, dokumentieren).
2. Sonst: **Konvergenz-Schleife der PDF-Erkennung** fortsetzen (`docs/PLAN.md` → „Konvergenz-Schleife“,
   Anleitung `tools/omr-eval/README.md`). Konkret:
   ```bash
   bash tools/omr-eval/setup.sh                                   # einmal pro Session
   bash tools/omr-eval/evalr.sh /tmp/omr-work/regress             # Ausgangswert: 87,6 / 91,1 / 89,5 %
   bash tools/omr-eval/evalr.sh /tmp/omr-work/rounds/r1           # Runde 1: nur 1–2 von 10 PDFs bei 100 %
   ```
   Offene Punkte dort (in dieser Reihenfolge): Choräle mit zwei Stimmen pro Notenzeile (Sekunden, gemeinsame
   Köpfe, Vorzeichen in Akkorden) → Tremolo-Schrägstrich (Salem Hornpipe) → Auftakt in `truth.py` →
   kleine „8“ am Violinschlüssel bei Verovio. Erst wenn Runde 1 sauber ist: neue Runde
   (`/tmp/omr-work/lyenv/bin/python tools/omr-eval/round.py 2 <seed>`), **Runde danach ins Repo sichern**
   (Befehl im README). Zähler „5 Runden hintereinander 100 %“: **0/5**.
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
- Lokal **kein Android-SDK** → App nur über die CI bauen; `./gradlew :core:test` läuft lokal.
- GitHub nur über die MCP-Tools (`mcp__github__*`); Job-Logs: `get_job_logs` mit `tail_lines`.
  Laufstatus geht auch per `curl https://api.github.com/repos/Quantonamor/PianoApp/actions/runs?branch=<branch>`.
- Hilfsdateien der Auswertung liegen in `/tmp/omr-work` (geht mit dem Container verloren → `setup.sh` legt sie neu an).
