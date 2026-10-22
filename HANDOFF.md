# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-22**, Branch `claude/gifted-dijkstra-m4qa41` (enthält alles von `claude/vibrant-faraday-id9x3t`
und davor `ccr-257ad710-x5upqq`). Allgemeine Regeln und Befehle stehen in `CLAUDE.md`.

## CI-Stand
Siehe letzter Lauf auf dem Branch. Im Emulator-Test sind die **Hände aus**: das Hochladen des Hand-Bitmaps in
jedem Frame lässt den Software-Renderer des Emulators abstürzen („Failed to find ColorBuffer“, danach „device
'emulator-5554' not found“). Die Hände werden stattdessen mit den JVM-Vorschauen geprüft:
`./gradlew :core:test --tests 'de.pianotiles.core.HandRenderTest' -Phands.out=<dir>` → `ode/scale/elise.png`.
**Bilder ansehen:** `mcp__github__get_job_logs` (ui-test-Job, `tail_lines` 500) speichert das Log als Datei →
`python3 tools/ci/extract_shots.py <datei> <zielordner>` → JPEGs mit Read ansehen.

## Stand der letzten Arbeitseinheit (Wünsche des Nutzers: Hände, Klang, Hochformat, Ausblenden, 50-Personen-Review)
- **Hände**: `core/hands/HandModel.kt` – WebXR-„generic-hand“ (MIT, © Amazon; `core/src/main/resources/hands/`,
  Konverter `tools/hands/convert_hand.py`). Software-Rasterizer (Skinning, z-Buffer, Licht, Rand-AA, Schatten,
  Unterarm-Ausblendung); IK je Finger (gedämpftes 2×2-Gauss-Newton über Spreizung/Beugung); die Hand rückt an die
  Tasten, bis Daumen/kleiner Finger reichen (max. 0,4 Mittelfinger-Längen). Fallback: alte SDF-Zeichnung in
  `HandRenderer.kt`. App rendert mit 40 % Auflösung (`HandsOverlay.kt`).
- **Klang**: `app/.../audio/Synth.kt` mit Klavier-Samples (`app/src/main/assets/piano/`, `tools/samples/make_piano.py`),
  Vorausplanung mit Verzögerung (`PracticeEngine.audioLookaheadSeconds`, `PlayNote.delaySeconds`).
- **Hochformat**: `fullSensor`; **Ausblenden**: beim Spielen und nach 6 s ohne Berührung.
- **MIDI**: `app/.../audio/MidiInput.kt`, verdrahtet in `PracticeViewModel` (`midiPitches`, `midiDevices`).
- **Review**: `docs/USER_REVIEW.md`; umgesetzt: Demos (9), Begrüßungskarte, Statistik
  (`core/practice/PracticeLog.kt` + `PracticeLogStore`), Mikrofon-Test (Einstellungen), Modus-Beschriftung.

## Danach (Reihenfolge, Details in `docs/PLAN.md` → M6)
0. Rückmeldung des Nutzers vom Gerät abwarten/einarbeiten (Hände, Klang, MIDI, Hochformat).
1. Optional „Fingersatz per KI“ (wie `core/ai/AiFixer.kt`, gleicher Schlüssel).
2. Konvergenz-Schleife der PDF-Erkennung (`docs/PLAN.md`, `tools/omr-eval/README.md`), Zähler 0/5. Neue
   Hinweise aus dem Notensatz-Rundlauf (`EngraveTest`, `-Pengrave.out=<dir>`): Auftakte mit Sechzehntel-Balken
   werden nicht erkannt (Balken-Lücke wird als hohler Notenkopf gelesen), 2/2 wird als 4/4 gelesen.

## Fertig in dieser Session
- **E) Notenbild**: `core/engrave/` (PDF-Schreiber + Notensatz mit Bravura-Symbolen), Stücke ohne Original
  bekommen `sheet.pdf` + Takt-Positionen (`SongRepository.withEngravedSheet`, `sheet.key`). Test `EngraveTest`.
- OMR: Tonart-Kreuz, das beim Linien-Entfernen in zwei Hälften zerfällt, wird erkannt (Regression unverändert 87,6/91,1/89,5).
- **D) 3D-Hände**: `core/hands/AutoFingering.kt` (Viterbi über Parncutt-Kosten; Tonleitern, Fünffinger-Lagen,
  Akkorde, Für Elise links 5-2-1), `core/hands/HandMotion.kt` (Handhaltung über die Zeit, `KeyX`), Tests `HandsTest`.
  App: `ui/practice/HandsOverlay.kt`, Fingerzahlen in `FallingNotes.kt`, Schalter ✋ (`PanTool`) im Übungsbildschirm,
  Einstellungen „Hände über der Klaviatur“/„Fingersatz auf den Noten“, Fingersatz beim Öffnen automatisch
  (`PracticeViewModel.withFingering`, gespeichert in `hands.json`), Editor: Finger einer Note ändern (Chip
  „Finger n“, Quelle wird MANUAL; automatische Fingersätze werden nach dem Bearbeiten neu berechnet).
- Fehler behoben: ein Stück, das gespeichert wurde, während die Bibliothek noch lud, ging verloren
  („Song not found“ nach Import direkt nach dem Start; `SongRepository.load` mischt jetzt).
- Hilfe, Benutzerhandbuch, Architektur, Changelog ergänzt.

## Was nur der Nutzer kann
- Test auf echtem Gerät (APK: GitHub Actions → letzter Lauf → Artefakt `PianoTiles-apk`), Fotos echter Noten,
  Aussehen der Hände beurteilen.
- Mikrofon mit echtem Klavier feinjustieren; KI-nachbessern mit eigenem API-Schlüssel.

## Umgebung
- Lokal kein Android-SDK → App nur über die CI; Maven Central drosselt manchmal (429) → Build wiederholen.
- GitHub nur über MCP-Tools (`mcp__github__*`); Laufstatus auch per
  `curl https://api.github.com/repos/Quantonamor/PianoApp/actions/runs?branch=claude/gifted-dijkstra-m4qa41`.
  Job-Logs laufender Jobs gibt es erst nach Ende des Jobs.
