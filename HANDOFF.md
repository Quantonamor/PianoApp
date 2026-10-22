# Übergabe für die nächste Session („weiter“)

**Wenn der Nutzer nur „weiter“ schreibt:** `docs/PLAN.md` lesen und den obersten offenen Punkt der
**Konvergenz-Schleife** fortsetzen (Anleitung: `tools/omr-eval/README.md`). Der Punkt **M5 (Editor 2.0 +
KI-nachbessern)** soll laut Nutzer **später** angegangen werden – erst wenn er es sagt oder die Schleife steht.

## Wichtigste Fakten
- Repo: `quantonamor/pianoapp`, Branch `ccr-257ad710-x5upqq` (alles committed & gepusht).
- Lokale Umgebung hat **kein Android-SDK** (dl.google.com gesperrt). `./gradlew :core:test` läuft lokal
  (Core = reines Kotlin). Die App baut **GitHub Actions** (`.github/workflows/android.yml`): Core-Tests,
  APK (Artefakt `PianoTiles-apk`), Emulator-Bedientest (`ui-test`, Screenshots als Artefakt).
  Ergebnisse über die GitHub-MCP-Tools (`actions_list`, `get_job_logs`) lesen.
- App-Quellen syntaktisch prüfen ohne SDK: `tools/omr-eval/kcheck.sh` (braucht Kotlin-Compiler aus dem Gradle-Cache).
- Doku: `README.md`, `docs/PLAN.md` (Plan/To-dos/Stand), `docs/ARCHITECTURE.md`, `docs/OMR.md`
  (Erkennung + Qualitätszahlen), `docs/USER_GUIDE.md`, `docs/CHANGELOG.md`.

## Offene Punkte direkt vor der Übergabe
- Emulator-Bedientest wurde auf manuell gesteuerte Compose-Uhr umgestellt (Übungsbildschirm animiert
  dauernd) – Ergebnis des letzten CI-Laufs prüfen; falls rot: Log-Zeile mit `TestRunner: failed` ansehen.
- Nutzer testet die App auf seinem Gerät → Rückmeldungen haben Vorrang vor der Schleife.
