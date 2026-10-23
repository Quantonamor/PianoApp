# Übergabe für die nächste Session („weiter“)

Stand: **2026-10-23**, Branch **`master`** (enthält alles aus den Cloud-Sessions `ccr-257ad710-x5upqq` →
`claude/vibrant-faraday-id9x3t` → `claude/gifted-dijkstra-m4qa41`). Ab jetzt wird **lokal in der IDE** auf `master`
weitergearbeitet. Allgemeine Regeln und Befehle: `CLAUDE.md`. Fahrplan: `docs/PLAN.md`. Verlauf: `docs/CHANGELOG.md`.

## Lokal loslegen (neu: echter Rechner mit Android-SDK)
```bash
./gradlew :core:test                       # alle Core-Tests (OMR-Regression, Klang, Hände, Importer …), ~2–3 Min.
./gradlew :app:assembleDebug               # APK → app/build/outputs/apk/debug/
./gradlew :app:installDebug                # auf angeschlossenes Gerät/Emulator
./gradlew :app:connectedDebugAndroidTest   # UI-Tests (SmokeTest + HandsShotTest) auf Gerät/Emulator
```
- **Hände-Vorschauen ohne Gerät:** `./gradlew :core:test --tests '*HandRenderTest*' -Phands.out=/tmp/hands` →
  `closeup*.png`, `app_portrait*.png`, `app_landscape.png`, Filmstreifen `strip*.png` (Bewegung, Daumenuntersatz).
- **Klang anhören:** `./gradlew :core:test --tests '*PianoSoundTest*' -Paudio.out=/tmp/audio` → WAVs aller Demos.
- **PDF-Erkennung messen:** `bash tools/omr-eval/setup.sh` (legt `/tmp/omr-work` an: LilyPond, music21, Verovio im
  venv), dann `bash tools/omr-eval/evalr.sh /tmp/omr-work/regress` (Anleitung `tools/omr-eval/README.md`).
- In der Cloud gab es kein Android-SDK; `tools/omr-eval/kcheck.sh` (Syntax-Check der App ohne SDK) ist lokal
  überflüssig – einfach `assembleDebug`.

## CI (GitHub Actions, `.github/workflows/android.yml`) – grün
- Jobs: `build` (Core-Tests, APK-Artefakt `PianoTiles-apk`), `ui-test` (Emulator API 34, `tools/ci/ui_tests.sh`).
- **Ton im Emulator aus:** Der CI-Emulator (ohne Audio-Gerät, `-noaudio`) verschwand reproduzierbar, sobald Musik
  spielte (kein App-Fehler, keine Absturzdaten). Die UI-Tests setzen deshalb `app.synth.outputEnabled = false`
  (Noten werden weiter geplant, nur kein AudioTrack). Seitdem grün im ersten Anlauf. Auf echten Geräten unverändert.
- Sicherheitsnetz: verschwindet der Emulator doch, schreibt `ui_tests.sh` die Datei `emulator_vanished` und die CI
  wiederholt den UI-Test einmal auf frischem Emulator (echte Testfehler bleiben rot).
- `HandsShotTest` (Hände an) läuft danach separat und **blockiert nicht** („==== HANDS TEST FAILED (not blocking)“):
  der Emulator-Renderer stürzt bei den Hand-Bitmaps manchmal ab („bad color buffer handle“).
- Screenshots stehen base64 im Log: Log als Datei holen → `python3 tools/ci/extract_shots.py <log> <ordner>`
  (Ordner vorher leeren). Bei Fehlern im Log: „==== FAILED TESTS ====“, „==== THREADS ====“, „LOGCAT TAIL“, „HOST“.
- **UI-Tests und Test-Uhr:** `mainClock.autoAdvance = false`; Aktionen, die Frames brauchen (`performScrollToNode`),
  mit `autoAdvance = true` umschließen. Jeder Test hat 5 Min. Timeout + Thread-Dump.

## Offene Punkte (in dieser Reihenfolge)
1. **Hände im pausierten Zustand (App):** Im CI-Screenshot `08_hands_paused` waren die Hände noch nicht zu sehen
   (Fingerziffern ja → Overlay aktiv, aber kein Bild). Behoben wurden schon: Bild wird nach dem Rendern neu
   gezeichnet (`HandsOverlay.kt`, `rendered`-Zähler) und Hände sind ab Schlag −∞ aktiv (`HandMotion.pose`).
   Letzter Verdacht: Rendern im Software-Emulator dauert > 1,5 s → Test wartet jetzt 4 s; die App loggt die
   Renderzeit (`adb logcat -s HandRender`). **Lokal auf echtem Gerät prüfen:** Stück öffnen, ✋ an, pausiert →
   Hände sichtbar? Renderzeit im Log? Falls langsam: Auflösung adaptiv senken (`HandsOverlay.kt`, `scale`).
2. **Rückmeldung vom echten Gerät:** Hände (Aussehen, Bewegung), Klang (Salamander-Samples), MIDI-Keyboard,
   Hochformat, Lernpfad. Ton auf echtem Gerät beim Abspielen (im Emulator war das der Absturzgrund – auf Geräten
   nicht erwartet, aber prüfen).
3. **Hände weiter verbessern** (Wunsch des Nutzers: „sehr viel mehr Energie“): siehe „Hände – Stand“ unten;
   Ideen: Sehnen/Knöchel auf dem Handrücken, weichere Bewegung bei großen Sprüngen, Daumen bei Oktaven,
   Performance-Messung auf Gerät.
4. **PDF-Erkennung, Konvergenz-Schleife** (`docs/PLAN.md` → „Konvergenz-Schleife“, Zähler 0/5): offen sind vor allem
   Choräle (zwei Stimmen je System): Sekund-Akkorde zweier Stimmen (versetzter Kopf wird eigene Spalte – ein
   Versuch, sie zusammenzulegen, verschlechterte den Rhythmus), punktierte Noten in Mittelstimmen, Balkenstücke
   als Notenköpfe (eine Eck-Regel verwarf zu viele echte Köpfe).
5. Optional „Fingersatz per KI“ (wie `core/ai/AiFixer.kt`).

## Stand der Bereiche (Kurzfassung, Details in `docs/ARCHITECTURE.md`)
- **Hände** – `core/hands/`: `HandModel.kt` (WebXR-„generic-hand“, MIT © Amazon, `core/src/main/resources/hands/`,
  Konverter `tools/hands/convert_hand.py`; Software-Rasterizer mit Skinning, z-Buffer, Licht, Nägeln, Hautfalten an
  den Gelenken, Kontaktschatten, 3×3-Kantenglättung; IK je Finger), `HandRenderer.kt` (Platzierung der Fingerspitzen
  auf den Tasten; Handgröße folgt der **echten Tastenbreite** `Geometry.keyW`; Unterarm zur Körpermitte `bodyX`;
  freie Finger nicht auf schwarzen Tasten `restMinY`; bei kurz gezeichneten Tasten perspektivisch gestaucht),
  `HandMotion.kt` (Haltung über die Zeit, Daumenuntersatz), `AutoFingering.kt` (Viterbi-Fingersatz).
  App: `ui/practice/HandsOverlay.kt` (Hintergrund-Thread, 3 Bitmaps, 50 % Auflösung, rendert nur bei geänderter
  Haltung). Tests: `HandsTest`, `HandRenderTest`.
- **Klang** – `core/audio/PianoSound.kt` (`PianoTones`: Salamander Grand, 3 Anschlagstufen; `PianoMixer`: Zeitplan,
  Stimmen, Hall, Kompressor), `app/.../audio/Synth.kt` (MP3-Dekodierung, AudioTrack, höchstens 0,2 s Vorlauf,
  `outputEnabled`). `PianoSoundTest`: jeder Ton genau einmal, ≤ 4 ms Schwankung, keine Übersteuerung.
- **Übungsbildschirm** – Leiste blendet beim Spielen und nach 6 s ohne Berührung aus; sie kommt beim **Loslassen** des
  Fingers zurück (sonst ging der Tipp auf den kleinen runden Zurück-Knopf verloren).
- **Lernpfad** – `core/practice/Course.kt`, `ui/library/CourseScreen.kt`, Sterne in `PracticeLogStore`.
- **Pedal & Dynamik** – `Song.pedal`, Import aus MIDI/MusicXML/MuseScore, „Ped.“-Streifen in `FallingNotes`.
- **PDF-Erkennung** – `core/omr/OmrEngine.kt`; Regressions-Satz (36 PDFs): **90,0 % Noten, 93,5 % Präzision,
  92,1 % Rhythmus**. Letzte Korrekturen: Noten mit Hals/Fähnchen nicht mehr als Taktangabe, zerfallenes „C“ (4/4),
  Fähnchen-Haken keine Ganzen Noten. Trace: `-Pomr.trace=1` (HEAD/SLOT/VOICES/TSBOX-Zeilen im Test-XML).

## Was nur der Nutzer kann
- Test auf echtem Gerät, Fotos echter Noten, Mikrofon mit echtem Klavier, KI-Nachbessern mit eigenem API-Schlüssel.
