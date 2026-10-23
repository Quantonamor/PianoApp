# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-22**, Branch `claude/gifted-dijkstra-m4qa41` (enthält alles von `claude/vibrant-faraday-id9x3t`
und davor `ccr-257ad710-x5upqq`). Allgemeine Regeln und Befehle stehen in `CLAUDE.md`.

## CI-Stand
Siehe letzter Lauf auf dem Branch. Im Emulator-Test sind die **Hände aus**: das Hochladen des Hand-Bitmaps in
jedem Frame lässt den Software-Renderer des Emulators abstürzen („Failed to find ColorBuffer“, danach „device
'emulator-5554' not found“). Die Hände werden stattdessen mit den JVM-Vorschauen geprüft:
`./gradlew :core:test --tests 'de.pianotiles.core.HandRenderTest' -Phands.out=<dir>` → `ode/scale/elise.png`.
**Bilder ansehen:** `mcp__github__get_job_logs` (ui-test-Job, `tail_lines` 500) speichert das Log als Datei →
`python3 tools/ci/extract_shots.py <datei> <zielordner>` → JPEGs mit Read ansehen. **Zielordner vorher leeren**
(sonst liegen alte Bilder daneben).
**UI-Tests und die Test-Uhr:** Die Tests stellen `mainClock.autoAdvance = false`. Aktionen, die Frames brauchen
(z. B. `performScrollToNode`), warten dann ewig auf „idle“ → vorher `autoAdvance = true`, danach wieder `false`.
Jeder Test hat 5 Min. Timeout; bei Fehlern stehen alle Thread-Stacks im Log („==== THREADS ====“).
**Emulator verschwand (Okt. 23):** Ursache war sehr wahrscheinlich der Audio-Thread: mit `-noaudio` blockiert
`AudioTrack.write` nicht, der Thread lief ungebremst – seit `Synth` höchstens 0,2 s vorausläuft, läuft der große
Bedientest durch. Bei Fehlern druckt die CI jetzt „==== HOST ====“ (Speicher, Kernel-Meldungen) und „LOGCAT TAIL“.
**Seit Okt. 23 rendert der Emulator im Gast (`-gpu guest`)**: der SwiftShader-Renderer des Runners stürzte immer
wieder ab („bad color buffer handle“); seitdem laufen alle Tests inkl. `HandsShotTest` durch und schneller.
**Stau vermeiden:** Commits bündeln, veraltete Läufe mit `actions_run_trigger` → `cancel_workflow_run` abbrechen.

## Stand der letzten Arbeitseinheit (Wünsche des Nutzers: Hände, Klang, Hochformat, Ausblenden, 50-Personen-Review)
- **Hände**: `core/hands/HandModel.kt` – WebXR-„generic-hand“ (MIT, © Amazon; `core/src/main/resources/hands/`,
  Konverter `tools/hands/convert_hand.py`). Software-Rasterizer (Skinning, z-Buffer, Licht, Rand-AA, Schatten,
  Unterarm-Ausblendung); IK je Finger (gedämpftes 2×2-Gauss-Newton über Spreizung/Beugung); die Hand rückt an die
  Tasten, bis Daumen/kleiner Finger reichen (max. 0,4 Mittelfinger-Längen). Fallback: alte SDF-Zeichnung in
  `HandRenderer.kt`. App rendert mit 50 % Auflösung im Hintergrund-Thread (`HandsOverlay.kt`, ~5 ms/Bild am Desktop).
- **Klang**: Mischer im Core (`core/audio/PianoSound.kt`: `PianoTones` = Salamander-Flügel, 3 Anschlagstufen p/m/f,
  Tonhöhe beim Mischen umgerechnet; `PianoMixer` = Zeitplan für vorausgeplante Töne, Stimmen, Hall, Kompressor).
  `app/.../audio/Synth.kt` dekodiert nur die MP3s (`assets/piano/<p|m|f>_<midi>.mp3`, `tools/samples/make_piano.py`)
  und füttert den AudioTrack. **`PianoSoundTest`** spielt alle Demos wie die App ab (jeder Ton genau einmal,
  ≤ 4 ms Schwankung, keine Übersteuerung); `-Paudio.out=<dir>` schreibt WAVs zum Anhören.
- **Hände (Stand 2)**: Finger verdickt (`thickenFingers`), PN-Unterteilung, Nägel (`markNails`), Rückseiten weg,
  Kontaktschatten, 3×3-Kantenglättung; Fingerspitzen gestaffelt (`HandRenderer.layout`), Vorschub max. 0,18;
  Rendern im Hintergrund-Thread (`HandsOverlay.kt`, 3 Bitmaps). CI: `HandsShotTest` läuft getrennt und darf
  scheitern (Screenshots `08*` im Log). Nahaufnahmen: `HandRenderTest` → `closeup.png`, `closeup_left.png`.
- **Hochformat**: `fullSensor`; **Ausblenden**: beim Spielen und nach 6 s ohne Berührung.
- **MIDI**: `app/.../audio/MidiInput.kt`, verdrahtet in `PracticeViewModel` (`midiPitches`, `midiDevices`).
- **Lernpfad**: `core/practice/Course.kt` (Lektionen, Sterne, Übungsstücke `ex-*`), `ui/library/CourseScreen.kt`
  (Liste + `CourseCard` in der Bibliothek), Sterne in `PracticeLogStore`, `Screen.Practice(lessonId)`;
  Test `CourseTest`, UI-Test `learningPath`.
- **Pedal & Dynamik**: `Song.pedal` (`PedalSpan`, `soundingEnd`), Import aus MIDI (CC64), MusicXML (`<pedal>`,
  `<dynamics>`), MuseScore (Pedal-Spanner, `Dynamic`, Velocity); Anzeige „Ped.“ in `FallingNotes`; `PedalTest`.
- **OMR (diese Session)**: oktavierter Violinschlüssel (verschmolzene 8), Alla breve, „6“ → 6/8, Auftakt mit
  Ganztaktpause, Ganzpause neben Noten verworfen, unvollständige Takte nacheinander, Punkt der einen Takt
  überfüllt wird verworfen, **Stimmentrennung nach Halsrichtung** (`splitVoices`). Runde 2 gespeichert
  (`tools/omr-eval/rounds/r2`, 4/10 bei 100 %). `truth.py`/`round.py` entfernen das music21-Auftakt-Polster.
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
