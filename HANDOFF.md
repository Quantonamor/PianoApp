# Übergabe für die nächste Session („weiter“)

**Wenn der Nutzer nur „weiter“ schreibt:** zuerst Rückmeldungen vom Gerätetest bearbeiten (falls vorhanden),
sonst `docs/PLAN.md` lesen und den obersten offenen Punkt der **Konvergenz-Schleife** fortsetzen
(Anleitung: `tools/omr-eval/README.md`). M1–M5 inkl. Editor 2.0, KI-nachbessern und englischer Oberfläche sind
fertig (Stand 2026-10-22, CI komplett grün); offen sind nur Dinge, die der Nutzer selbst testen muss
(echtes Gerät, Mikrofon am Klavier, KI-nachbessern mit eigenem API-Schlüssel).

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
- Emulator-Bedientest ist grün (manuell gesteuerte Compose-Uhr; nach Klicks Uhr vorstellen, z. B. `waitText`/`play`).
  Bei Fehlern druckt die CI unter „==== FAILED TESTS ====“ die Fehlermeldung.
- KI-nachbessern nutzt das offizielle Anthropic-Java-SDK im `core`-Modul (`core/ai/`), Modell `claude-opus-5-5`;
  ohne API-Schlüssel nicht live getestet (nur mit Fake-Reviewer in `AiAndEditTest`).
- Texte: `tr("deutscher Text")` (App, Wörterbuch `app/i18n/English.kt`) bzw. `CoreText.t(de, en)` (Core).
  Neue Texte immer mit Übersetzung anlegen.
- Nutzer testet die App auf seinem Gerät → Rückmeldungen haben Vorrang vor der Schleife.
