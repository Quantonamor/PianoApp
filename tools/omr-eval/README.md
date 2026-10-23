# OMR-Auswertung & Konvergenz-Schleife

Werkzeuge, mit denen die PDF-Notenerkennung gegen bekannte Lösungen gemessen und verbessert wird.

## Einrichtung (einmal pro neuer Session/Container, ~1 Minute)

```bash
bash tools/omr-eval/setup.sh          # legt $OMR_WORK an (Standard: /tmp/omr-work)
```
Läuft unter Linux und unter Windows in Git Bash (`/tmp` = `%LOCALAPPDATA%\Temp`). Python im venv:
`source tools/omr-eval/env.sh; "$VPY" …` (Linux: `lyenv/bin/python`, Windows: `lyenv/Scripts/python.exe`).
Danach gibt es in `/tmp/omr-work`: `lyenv/` (LilyPond, music21, Verovio), `rounds/` (alle bisherigen Runden aus
`tools/omr-eval/rounds/` + `used.txt`), `regress/` (fester Korpus + alle Runden als Links), `omr/` (Overlay-Bilder),
`kcp.txt` (Classpath für `kcheck.sh`). Alle Skripte lesen `OMR_WORK`, Pfade zum Repo ermitteln sie selbst.

## Werkzeuge

| Datei | Zweck |
|---|---|
| `make_corpus.py` | erzeugt den festen 14-Stücke-Korpus (liegt fertig in `core/src/test/resources/corpus`) |
| `truthlib.py` | erzeugt `truth.txt` (Lösung: `midi start dauer hand`) aus einer Partitur; Startzeiten folgen dem Druckbild (Auftakte/unvollständige Takte, die music21 mit unsichtbaren Pausen auffüllt) |
| `truth.py [DIR]` | schreibt `truth.txt` aus `score.musicxml` in jedem Unterordner |
| `rescore.py DIR…` | erzeugt die Lösungen vorhandener Stücke (Korpus, Runden) neu aus dem music21-Korpus, ohne PDFs neu zu setzen |
| `env.sh` | gemeinsame Pfade/Python-Aufrufe für alle Skripte (Linux und Git Bash unter Windows) |
| `setup.sh` | Einrichtung (siehe oben) |
| `round.py N SEED [ANZAHL]` | neue Zufallsrunde: zufällige Bach-Choräle (als Klaviersatz) und Volkslieder aus dem music21-Korpus, je als LilyPond- und Verovio-PDF → `$OMR_WORK/rounds/rN/` – aufrufen mit `/tmp/omr-work/lyenv/bin/python tools/omr-eval/round.py N SEED` |
| `evalr.sh DIR [-Pomr.only=NAME] [-Pomr.trace=1]` | wertet einen Ordner aus (OK/FAIL je PDF, Noten/Präzision/Rhythmus, Fehlerarten). Mit `DIFF=1` werden die ersten Abweichungen gezeigt; Overlays landen in `$OMR_WORK/omr/`. Aufruf: `bash tools/omr-eval/evalr.sh /tmp/omr-work/regress` |
| `kcheck.sh` | schneller Syntax-Check der App-Quellen (ohne Android-SDK) |
| `rounds/r1` | Runde 1 (5 Stücke) mit PDFs + Lösungen |

## Konvergenz-Schleife (Ziel des Nutzers)

1. `/tmp/omr-work/lyenv/bin/python tools/omr-eval/round.py N SEED` → 5 neue Zufallsstücke (N = nächste freie Nummer, siehe `tools/omr-eval/rounds/`).
2. `bash tools/omr-eval/evalr.sh /tmp/omr-work/rounds/rN` → jedes PDF muss **100 % Noten, 100 % Präzision, 100 % Rhythmus** haben.
3. Fehler analysieren (`DIFF=1`, Overlay-PNGs, `-Pomr.trace=1`), in `OmrEngine.kt` beheben.
4. Gegenprüfen: Regressions-Satz = fester Korpus + alle bisherigen Runden (`bash tools/omr-eval/setup.sh` erneut ausführen, dann `evalr.sh /tmp/omr-work/regress`) darf nicht schlechter werden;
   `./gradlew :core:test` muss grün sein.
5. **Runde sichern** (sonst geht sie mit dem Container verloren):
   `for d in /tmp/omr-work/rounds/rN/*/; do t=tools/omr-eval/rounds/rN/$(basename $d); mkdir -p $t; cp $d/{lilypond.pdf,verovio.pdf,truth.txt} $t/ 2>/dev/null; done`
   und `cp /tmp/omr-work/rounds/used.txt tools/omr-eval/used_pieces.txt`, Ergebnis in `docs/PLAN.md` eintragen, committen.
6. Ziel erreicht, wenn **5 Runden hintereinander** alle Stücke 100 % haben.

Stand siehe `docs/PLAN.md` → „Konvergenz-Schleife“.
