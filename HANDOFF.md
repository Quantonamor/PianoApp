# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-22**, Branch `claude/gifted-dijkstra-m4qa41` (enthält alles von `claude/vibrant-faraday-id9x3t`
und davor `ccr-257ad710-x5upqq`). Allgemeine Regeln und Befehle stehen in `CLAUDE.md`.

## CI-Stand
Letzter Lauf (`12bafc8`) **komplett grün**: Core-Tests, APK, Emulator-Bedientest mit eingeschalteten Händen.
Lehre daraus: Die erste Hände-Zeichnung (freie Pfade mit schrägen Farbverläufen) hat den Software-Renderer des
Emulators abstürzen lassen („device 'emulator-5554' not found“). Deshalb zeichnet `HandsOverlay.kt` nur noch mit
einfachen Formen (runde Linien, abgerundete Rechtecke, Kreise). Nach einem Absturz begrenzt `timeout 60` im Workflow
das Warten von adb (vorher 40 Min. Hänger).

## Rückmeldung des Nutzers vom Gerät (2026-10-22) – in Arbeit
„Sound nicht gut, beim Abspielen spielt er nichts zusammen, manchmal fehlen Noten; viele Überlappungen der GUI,
vieles sollte ausblendbar / standardmäßig aus sein.“ Umgesetzt (`429e7c1`):
- `app/.../audio/Synth.kt` neu: Töne je Tonhöhe vorberechnet (Teiltöne, zweistufiges Abklingen, Saitenpaar,
  Hammer), Audio-Thread mischt nur noch → Akkorde gleichzeitig; 48 Stimmen, weiches Dämpfen statt Abschneiden;
  `prepare()` beim Öffnen und vor jedem Akkord. „Fließend“ ohne Mikrofon spielt auch die geübte Hand
  (`PracticeEngine.playOwnHand`).
- Übungsbildschirm: Menü „Ansicht“ (Auge) für Hände, Fingersatz, Notennamen, Taktnummern, Metronom; kompakte
  Kopfzeile auf Handys; Gesten-Hinweis nur vor dem ersten Start; Hände/Fingersatz/Tastennamen standardmäßig aus
  (neue Pref-Schlüssel `hands2`, `fingers2`, `namesKeys2`).
- CI: Emulator jetzt Handy (`pixel_6`), Screenshots stehen verkleinert als base64 im Job-Log
  („==== SHOT name ====“) – damit Überlappungen prüfen (Artefakte lassen sich hier nicht herunterladen).
Danach (Referenz-Screenshots des Nutzers: „Artie“/„fallingnoteapp“): neue Optik der fallenden Noten und der
Klaviatur, Leisten blenden sich beim Spielen aus, Hoch- **und** Querformat (`fullUser`), Bibliothek/Editor schmal.
**Bilder ansehen:** `mcp__github__get_job_logs` (ui-test-Job, `tail_lines` 500) speichert das Log als Datei →
`python3 /tmp/claude-0/extract.py <datei>` (Skript: SHOT-Zeilen base64 → JPEG; siehe Verlauf, neu schreiben falls weg)
→ JPEGs mit Read ansehen. Der Test läuft im Hochformat mit 130 % Schrift und macht zusätzlich Querformat-Bilder.
**Offen:** Hände-Zeichnung ist noch unschön (standardmäßig aus); Nutzer um erneuten Gerätetest bitten.

## Danach (Reihenfolge, Details in `docs/PLAN.md` → M6)
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
