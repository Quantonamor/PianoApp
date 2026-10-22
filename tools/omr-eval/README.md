# OMR-Auswertung & Konvergenz-Schleife

Werkzeuge, mit denen die PDF-Notenerkennung gegen bekannte Lösungen gemessen und verbessert wird.

## Einrichtung (einmalig pro Session)

```bash
python3 -m venv /tmp/claude-0/lyenv
/tmp/claude-0/lyenv/bin/pip install lilypond music21 verovio cairosvg   # PyPI ist erreichbar
cp tools/omr-eval/*.py tools/omr-eval/*.sh /tmp/claude-0/ && chmod +x /tmp/claude-0/*.sh
cp tools/omr-eval/used_pieces.txt /tmp/claude-0/rounds/used.txt   # bereits benutzte Stücke
```
`make_corpus.py` erwartet LilyPond unter `/tmp/claude-0/lyenv/lib/python3.13/site-packages/lilypond-binaries/bin`.

## Werkzeuge

| Datei | Zweck |
|---|---|
| `make_corpus.py` | erzeugt den festen 14-Stücke-Korpus (liegt fertig in `core/src/test/resources/corpus`) |
| `truth.py` | schreibt `truth.txt` (Lösung: `midi start dauer hand`) aus `score.musicxml` |
| `round.py N SEED [ANZAHL]` | neue Zufallsrunde: zufällige Bach-Choräle (als Klaviersatz) und Volkslieder aus dem music21-Korpus, je als LilyPond- und Verovio-PDF → `/tmp/claude-0/rounds/rN/` |
| `evalr.sh DIR [-Pomr.only=NAME] [-Pomr.trace=1]` | wertet einen Ordner aus (OK/FAIL je PDF, Noten/Präzision/Rhythmus, Fehlerarten). Mit `DIFF=1` werden die ersten Abweichungen gezeigt; Overlays landen in `/tmp/claude-0/omr/` |
| `kcheck.sh` | schneller Syntax-Check der App-Quellen (ohne Android-SDK) |
| `rounds/r1` | Runde 1 (5 Stücke) mit PDFs + Lösungen |

## Konvergenz-Schleife (Ziel des Nutzers)

1. `round.py N SEED` → 5 neue Zufallsstücke.
2. `evalr.sh /tmp/claude-0/rounds/rN` → jedes PDF muss **100 % Noten, 100 % Präzision, 100 % Rhythmus** haben.
3. Fehler analysieren (`DIFF=1`, Overlay-PNGs, `-Pomr.trace=1`), in `OmrEngine.kt` beheben.
4. Gegenprüfen: Regressions-Satz = fester Korpus + alle bisherigen Runden (`/tmp/claude-0/regress`) darf nicht schlechter werden;
   `./gradlew :core:test` muss grün sein.
5. Ziel erreicht, wenn **5 Runden hintereinander** alle Stücke 100 % haben.

Stand siehe `docs/PLAN.md` → „Konvergenz-Schleife“.
