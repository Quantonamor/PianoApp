# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-22**, Branch `claude/vibrant-faraday-id9x3t` (vorher `ccr-257ad710-x5upqq`, dort CI grün).
Allgemeine Regeln und Befehle stehen in `CLAUDE.md`. Session wurde wegen Token-Ende vorzeitig beendet.

## ZUERST: CI prüfen und reparieren (wichtig!)
Der letzte Push enthält viel **neuen App-Code, der lokal nur auf Syntax geprüft ist** (kein Android-SDK).
CI (`.github/workflows/android.yml`) für diesen Branch ansehen, Compile-Fehler im APK-Build beheben, dann
Emulator-Test. Betroffene Dateien:
`app/.../data/SongRepository.kt` (komplett neu: Ordner je Stück, Migration, Pakete, Cover), `data/SheetDocument.kt` (neu),
`data/Sharing.kt`, `importing/ScoreImporter.kt` (alle Formate + Fotos, braucht `androidx.exifinterface`),
`ui/importflow/ImportViewModel.kt` + `ImportScreen.kt` (Liste von Uris), `ui/importflow/PhotoCaptureScreen.kt` (neu),
`ui/library/LibraryScreen.kt` (komplett neu), `ui/practice/SheetPanel.kt`/`PracticeScreen.kt`/`PracticeViewModel.kt`
(`listen`-Parameter), `ui/editor/EditorViewModel.kt` (SheetDocument statt PdfRenderer), `MainActivity.kt`
(Screen.Import(uris), Screen.Photos, Practice(listen)), `AndroidManifest.xml`, `res/xml/file_paths.xml`.
Neue Smoke-Tests in `SmokeTest.kt`: `playFromLibrary`, `importAbc`, `importPhoto`, `packageRoundTrip`.
Englische Übersetzungen der neuen Texte sind eingetragen (`English.kt`, Abschnitt „M6“); bei neuen `tr("…")`-Texten
weiterhin dort ergänzen.

## Danach (Reihenfolge, Details in `docs/PLAN.md` → M6)
1. **D) 3D-Hände**: Core `core/hands/` – automatischer Fingersatz (DP: Spannweite, Daumen-Unter-/Übersatz,
   schwarze Tasten, Akkorde) → `Note.finger` (Modell und `hands.json` sind schon da, `FingeringSource`);
   Handhaltung über die Zeit; App: perspektivisch gezeichnete, schattierte Hände über der Klaviatur, Schalter
   „Hände“, Fingerzahlen auf den Noten; optional „Fingersatz per KI“ (wie `core/ai/AiFixer.kt`).
2. **E) Notenbild `sheet.pdf`** aus der Noten-Datei setzen (einfacher eigener Notensatz, mit Takt-Positionen),
   damit auch MIDI/MusicXML-Stücke ein Notenblatt haben (`SongRepository.storeSheet` existiert schon).
3. Konvergenz-Schleife der PDF-Erkennung (`docs/PLAN.md`, `tools/omr-eval/README.md`), Zähler 0/5.

## Fertig in dieser Session (Core, lokal getestet – `./gradlew :core:test` grün für die neuen Tests)
- `core/io/MusicXmlExporter.kt` (+ `KeyGuess`), `core/library/SongPackage.kt`, `Song.keyFifths`, `Note.finger`,
  `FingeringSource`, neue `SongSource`-Werte. Tests: `PackageTest`.
- `core/io/AbcImporter.kt`, `MuseScoreImporter.kt`, `MeiImporter.kt`, `Repeats.kt`. Tests: `AbcTest`, `MuseScoreTest`,
  `FormatCrossCheck` (gegen music21; eigene Daten: `-Pformats.dir=<dir>`, Generator `tools/formats/gen.py`
  mit `/tmp/omr-work/lyenv/bin/python`).
- `core/omr/PhotoPrep.kt`. Test: `PhotoTest` (Knöpfe `-Pphoto.noise`, `-Pphoto.angle`, `-Pomr.debugDir`).
- Volle Core-Testsuite (`./gradlew :core:test`, inkl. OMR-Korpus) in dieser Session **nicht** komplett gelaufen –
  CI macht das; bei Regression dort ansetzen (OmrEngine nur um `keyFifths` am Song ergänzt).

## Was nur der Nutzer kann
- Test auf echtem Gerät (APK: GitHub Actions → letzter Lauf → Artefakt `PianoTiles-apk`), Fotos echter Noten.
- Mikrofon mit echtem Klavier feinjustieren; KI-nachbessern mit eigenem API-Schlüssel.

## Umgebung
- Lokal kein Android-SDK → App nur über die CI; Maven Central drosselt manchmal (429) → Build wiederholen.
- GitHub nur über MCP-Tools (`mcp__github__*`); Laufstatus auch per
  `curl https://api.github.com/repos/Quantonamor/PianoApp/actions/runs?branch=claude/vibrant-faraday-id9x3t`.
- `bash tools/omr-eval/setup.sh` legt `/tmp/omr-work` neu an (auch für `kcheck.sh`).
