# PianoTiles – Hinweise für Claude (wird in jeder Session automatisch gelesen)

Der Nutzer schreibt Deutsch und will, dass du **völlig selbstständig** arbeitest: Schreibt er nur „weiter“,
machst du ohne Rückfrage mit dem nächsten offenen Punkt weiter. Alles wird dokumentiert.

## Erste Schritte in jeder neuen Session
1. `HANDOFF.md` lesen (aktueller Stand + was als Nächstes kommt), dann `docs/PLAN.md` (Fahrplan, Checklisten,
   Konvergenz-Schleife mit Zahlen).
2. Branch: **`master`** (seit 2026-10-23; vorher Cloud-Branches `ccr-257ad710-x5upqq` → `claude/vibrant-faraday-id9x3t`
   → `claude/gifted-dijkstra-m4qa41`; die OMR-Konvergenz-Kette `ccr-bbbc1057-0ev5g2` → `ccr-564d5418-uo5d3u` wurde
   am 2026-10-23 mit `master` zusammengeführt). Gibt eine Session einen anderen Branch vor: vom neuesten Stand
   (`master` bzw. dem zusammengeführten Branch, `git log`) aus weitermachen.
3. Für Arbeit an der PDF-Erkennung: `bash tools/omr-eval/setup.sh` (≈1 Min., legt `/tmp/omr-work` an).
4. CI-Stand prüfen (`gh run list` bzw. GitHub-MCP `actions_list` / `get_job_logs`, Repo `quantonamor/pianoapp`) –
   rote CI zuerst reparieren.

## Was das Projekt ist
Android-App (Kotlin, Jetpack Compose, Hoch- und Querformat) zum Klavierüben wie Synthesia/„Piano Tiles“: Noten fallen auf
eine Klaviatur, das Mikrofon hört zu (Warte-Modus) oder es läuft im Tempo durch; Schleifen, Hände getrennt,
Tempo-Treppe. Eigene Bibliothek durch Import von **PDF-Noten (eigene OMR)**, MIDI, MusicXML. Noten-Editor mit
Original-PDF und „KI-nachbessern“ (Claude-API mit eigenem Schlüssel des Nutzers). Deutsch + Englisch.

## Aufbau
- `core/` – reines Kotlin/JVM, lokal testbar: `model/`, `io/` (MIDI/MusicXML), `omr/` (PDF-Notenerkennung,
  `OmrEngine.kt` ist das Herzstück), `audio/` (FFT-Tonerkennung), `practice/` (Übungs-Engine), `edit/`
  (Takt-Werkzeuge), `ai/` (Prompt + Claude-Client über offizielles Anthropic-Java-SDK), `i18n/`.
- `core/hands/` (3D-Hände: Modell, Rasterizer, IK, Fingersatz), `core/audio/PianoSound.kt` (Klang-Mischer).
- `app/` – Android-Oberfläche (Compose). Braucht ein Android-SDK (lokal in der IDE vorhanden, in der Cloud nicht).
- Details: `docs/ARCHITECTURE.md`, `docs/OMR.md`, Bedienung: `docs/USER_GUIDE.md`, Verlauf: `docs/CHANGELOG.md`.

## Wichtige Befehle
- `./gradlew :core:test` – alle Core-Tests (inkl. OMR-Regression auf dem Korpus), lokal ~3 Min.
- `./gradlew :app:assembleDebug` / `:app:installDebug` / `:app:connectedDebugAndroidTest` – App bauen, aufs Gerät,
  UI-Tests (`SmokeTest`, `HandsShotTest`) auf Gerät/Emulator.
- `./gradlew :core:test --tests '*HandRenderTest*' -Phands.out=<dir>` – Bilder der 3D-Hände;
  `--tests '*PianoSoundTest*' -Paudio.out=<dir>` – WAVs der Demos.
- `bash tools/omr-eval/evalr.sh /tmp/omr-work/regress` – OMR-Qualität messen (Anleitung `tools/omr-eval/README.md`).
- `bash tools/omr-eval/kcheck.sh` – Syntax-Check der App ohne Android-SDK (nur in der Cloud nötig).
- CI: GitHub Actions `.github/workflows/android.yml` (Core-Tests, APK-Artefakt `PianoTiles-apk`, Emulator-UI-Tests
  über `tools/ci/ui_tests.sh`, im Emulator ohne Ton). Bei UI-Test-Fehlern: Log unter „==== FAILED TESTS ====“.
- **Lokal am Windows-PC des Nutzers (VS Code)** gibt es ein Android-SDK und Emulatoren (`emulator -list-avds`):
  `local.properties` mit `sdk.dir=C:/Users/Martin/AppData/Local/Android/Sdk` (Schrägstriche!), dann
  `bash tools/emu.sh start`, `bash tools/emu.sh install`, `tools/emu.sh shot NAME` (Screenshot ansehen),
  `tools/emu.sh tapt "Text"` (tippt auf Element mit diesem Text).
- **In der Cloud-Session baut die App nur die CI** (kein Android-SDK, dl.google.com gesperrt) – nach jedem Push die
  CI prüfen.

## Konventionen
- Texte in der App immer über `tr("Deutscher Text")` bzw. `tr("… %s …", arg)` und Eintrag in
  `app/src/main/kotlin/de/pianotiles/app/i18n/English.kt`; im Core `CoreText.t(de, en)`.
- Musikalische Zeiten in Schlägen (Viertel), Takte 0-basiert (Auftakt = Takt 0), MIDI-Tonhöhen.
- Der Smoke-Test nutzt eine manuell gesteuerte Compose-Uhr: nach Klicks die Uhr vorstellen (`waitText`/`play`).
- Commits: aussagekräftig, auf Englisch; nach jeder abgeschlossenen Einheit committen und auf `master` pushen.
- Nach jeder Arbeitseinheit `docs/PLAN.md`, `docs/CHANGELOG.md` und `HANDOFF.md` aktualisieren.
