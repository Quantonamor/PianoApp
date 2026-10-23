# PianoTiles – Hinweise für Claude (wird in jeder Session automatisch gelesen)

Der Nutzer schreibt Deutsch und will, dass du **völlig selbstständig** arbeitest: Schreibt er nur „weiter“,
machst du ohne Rückfrage mit dem nächsten offenen Punkt weiter. Alles wird dokumentiert.

## Erste Schritte in jeder neuen Session
1. `HANDOFF.md` lesen (aktueller Stand + was als Nächstes kommt), dann `docs/PLAN.md` (Fahrplan, Checklisten,
   Konvergenz-Schleife mit Zahlen).
2. Branch: der Branch, den die Session vorgibt. Bisher wurde auf `ccr-257ad710-x5upqq`, dann `ccr-bbbc1057-0ev5g2` entwickelt
   (`git log` zeigt den Verlauf). Falls die Session einen neuen Branch vorgibt: von diesem Stand aus weitermachen.
3. Für Arbeit an der PDF-Erkennung: `bash tools/omr-eval/setup.sh` (≈1 Min., legt `/tmp/omr-work` an).
4. CI-Stand prüfen (GitHub-MCP `actions_list` / `get_job_logs`, Repo `quantonamor/pianoapp`) – rote CI zuerst reparieren.

## Was das Projekt ist
Android-App (Kotlin, Jetpack Compose, Querformat) zum Klavierüben wie Synthesia/„Piano Tiles“: Noten fallen auf
eine Klaviatur, das Mikrofon hört zu (Warte-Modus) oder es läuft im Tempo durch; Schleifen, Hände getrennt,
Tempo-Treppe. Eigene Bibliothek durch Import von **PDF-Noten (eigene OMR)**, MIDI, MusicXML. Noten-Editor mit
Original-PDF und „KI-nachbessern“ (Claude-API mit eigenem Schlüssel des Nutzers). Deutsch + Englisch.

## Aufbau
- `core/` – reines Kotlin/JVM, lokal testbar: `model/`, `io/` (MIDI/MusicXML), `omr/` (PDF-Notenerkennung,
  `OmrEngine.kt` ist das Herzstück), `audio/` (FFT-Tonerkennung), `practice/` (Übungs-Engine), `edit/`
  (Takt-Werkzeuge), `ai/` (Prompt + Claude-Client über offizielles Anthropic-Java-SDK), `i18n/`.
- `app/` – Android-Oberfläche (Compose). Wird nur gebaut, wenn ein Android-SDK da ist.
- Details: `docs/ARCHITECTURE.md`, `docs/OMR.md`, Bedienung: `docs/USER_GUIDE.md`, Verlauf: `docs/CHANGELOG.md`.

## Wichtige Befehle
- `./gradlew :core:test` – alle Core-Tests (inkl. OMR-Regression auf dem Korpus), lokal ~3 Min.
- `bash tools/omr-eval/kcheck.sh` – Syntax-Check der App-Quellen ohne Android-SDK (nach `setup.sh`).
- `bash tools/omr-eval/evalr.sh /tmp/omr-work/regress` – OMR-Qualität messen (Anleitung `tools/omr-eval/README.md`).
- **Lokal am Windows-PC des Nutzers (VS Code)** gibt es ein Android-SDK und Emulatoren (`emulator -list-avds`):
  `local.properties` mit `sdk.dir=C:/Users/Martin/AppData/Local/Android/Sdk` (Schrägstriche!), dann
  `bash tools/emu.sh start`, `bash tools/emu.sh install`, `tools/emu.sh shot NAME` (Screenshot ansehen),
  `tools/emu.sh tapt "Text"` (tippt auf Element mit diesem Text). Bedientest lokal:
  `./gradlew :app:connectedDebugAndroidTest` (installiert über vorhandene App-Daten, deinstalliert danach die
  App samt Daten → anschließend `emu.sh install`).
- **In der Cloud-Session baut die App nur die CI** (GitHub Actions `.github/workflows/android.yml`: Core-Tests, APK als
  Artefakt `PianoTiles-apk`, Emulator-Bedientest `app/src/androidTest/.../SmokeTest.kt`). Lokal gibt es kein
  Android-SDK (dl.google.com gesperrt) – also nach jedem Push die CI prüfen. Bei UI-Test-Fehlern steht die
  Meldung im Log unter „==== FAILED TESTS ====“.

## Konventionen
- Texte in der App immer über `tr("Deutscher Text")` bzw. `tr("… %s …", arg)` und Eintrag in
  `app/src/main/kotlin/de/pianotiles/app/i18n/English.kt`; im Core `CoreText.t(de, en)`.
- Musikalische Zeiten in Schlägen (Viertel), Takte 0-basiert (Auftakt = Takt 0), MIDI-Tonhöhen.
- Der Smoke-Test nutzt eine manuell gesteuerte Compose-Uhr: nach Klicks die Uhr vorstellen (`waitText`/`play`).
- Commits: aussagekräftig, auf Englisch; nach jeder abgeschlossenen Einheit committen und pushen.
- Nach jeder Arbeitseinheit `docs/PLAN.md`, `docs/CHANGELOG.md` und `HANDOFF.md` aktualisieren.
