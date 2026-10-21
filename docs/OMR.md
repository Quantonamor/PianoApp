# PDF-Notenerkennung (OMR)

Die App wandelt PDF-Noten **offline auf dem Gerät** in spielbare Noten um. Implementierung:
`core/src/main/kotlin/de/pianotiles/core/omr/`.

## Ablauf

1. **Rendern** (`app/importing/ScoreImporter`): Jede PDF-Seite wird mit `PdfRenderer` auf 2000 px
   Breite gerendert (≈ 16–20 px Linienabstand bei A4) und in Graustufen umgewandelt.
2. **Binarisieren**: Otsu-Schwellwert.
3. **Notenlinien** (`StaffDetector`): Lauflängen-Statistik liefert Liniendicke und -abstand; Zeilen mit
   langen waagrechten Läufen sind Linienkandidaten; je 5 gleichabständige Linien bilden ein System.
4. **Systeme**: Zwei Notenzeilen, die links durch eine senkrechte Linie (Akkolade) verbunden sind,
   bilden ein Klaviersystem (oben Violin-, unten Bassschlüssel). Einstellbar: Automatisch / Klavier /
   nur Violin- / nur Bassschlüssel.
5. **Linien entfernen**: Pixel, deren senkrechter Lauf nicht länger als die Liniendicke ist, werden gelöscht.
6. **Löcher füllen**: Kleine, ovale, umschlossene weiße Flächen sind Löcher halber/ganzer Noten. Sie
   werden gefüllt (Köpfe auf einer Linie: die Linie wird im Kopf wiederhergestellt). Rechteckige Zellen
   (zwischen Hals, Balken, Linien) werden über die Form ausgeschlossen.
7. **Öffnung** (Erosion + Dilatation mit ≈ 0,45 Linienabständen) entfernt Hälse, Linien, dünne Zeichen;
   übrig bleiben Notenköpfe (einzeln, nebeneinander bei Sekunden, gestapelt bei Akkorden).
8. **Filter**: Taktangabe (Ziffern oben/unten bzw. „C“), Kopfgröße, Hilfslinien-Pflicht außerhalb des
   Systems (entfernt Tempoangaben, Fingersätze, Text), „Geister“ an Balkenecken.
9. **Hälse, Balken, Fähnchen, Punkte**: Senkrechter dünner Strich ≥ 2,2 Abstände am Kopfrand; Balken
   werden am Halsende gezählt (1 = Achtel, 2 = Sechzehntel); Augmentationspunkt rechts neben dem Kopf.
10. **Pausen**: Viertel-, Achtel- und halbe Pausen über Form/Lage.
11. **Taktstriche**: dünne Striche über die ganze Notenzeile, die kein Hals sind.
12. **Vorzeichen**: Kreuz (zwei parallele Striche), b (Strich mit Bauch), Auflösungszeichen; gelten bis
    Taktende. **Tonart** aus den Vorzeichen nach dem Schlüssel.
13. **Rhythmus je Takt**: Spalten (Akkorde) nacheinander mit ihren Dauern. Stimmt die Summe nicht mit
    der Taktlänge überein, werden die Einsätze aus der anderen Hand bzw. der waagrechten Position
    interpoliert (Warnung „Rhythmus geschätzt in Takt …“). Auftakt wird erkannt.
14. **Taktart**: häufigste Taktsumme (oder manuell).

## Qualität (automatisch getestet)

### 1. Eigene Referenzstücke (`OmrRealScoreTest`)

Drei mit LilyPond gesetzte Stücke, verglichen mit dem MIDI derselben Quelle:

| Stück | Noten | Erkannt (Tonhöhe + Zeitpunkt) | Fehlalarme |
|---|---|---|---|
| Ode an die Freude (C-Dur, 4/4, ganze/halbe Noten, Akkorde) | 101 | 100 % | 0 |
| Menuett G-Dur (Petzold, 3/4, Achtel mit Balken, punktierte Halbe) | 94 | 100 % | 0 |
| Kleine Etüde (D-Dur, Sechzehntel, Pause, Auflösungszeichen) | 58 | 100 % | 0 |

### 2. Testkorpus mit „fremden“ PDFs (`OmrCorpusEval`)

14 gemeinfreie Stücke aus dem *music21*-Korpus, jeweils mit **zwei verschiedenen Notensatz-Programmen**
(LilyPond und Verovio) als PDF erzeugt – 26 PDFs, insgesamt ~45 Seiten. Die Lösung stammt aus der
MusicXML-Quelle. Gemessen wird per Sequenzabgleich:

* **Noten** = Anteil der Originalnoten, die mit richtiger Tonhöhe in richtiger Reihenfolge erkannt wurden
* **Präzision** = Anteil der erkannten Noten, die stimmen
* **Rhythmus** = Anteil der korrekten Zeitabstände zwischen aufeinanderfolgenden erkannten Noten

| Stück | Art | Noten | Rhythmus |
|---|---|---|---|
| Mozart, Sonate KV 545 (Exposition) | Klavier, Schlüsselwechsel, Sechzehntel | 98–100 % | 99–100 % |
| Amy Beach, Prayer of a tired child | Chor + Klavier (6 Zeilen) | 94–98 % | 97–100 % |
| Volkslied (Essen-Sammlung) | einstimmig, 4/2-Takt | 92–95 % | 95–100 % |
| O'Neill's Irish Music | einstimmig, Verzierungen | 83–95 % | 68–88 % |
| „Aloha ʻOe“ | Gesang + Klavier (7 Zeilen) | 90–92 % | 86–91 % |
| Bach-Choräle BWV 66.6, 269, 347 (Klaviersatz) | 2 Stimmen pro Zeile | 83–92 % | 84–93 % |
| C. P. E. Bach H. 186 | Verzierungen, 5er-/13er-Tolen | 82 % | 33 % |
| Schubert, Der Lindenbaum | Gesang + Klavier, Triolen | 74–81 % | 74–79 % |
| Schumann, Dichterliebe Nr. 2 | Gesang + Klavier | 74 % | 86 % |
| Joplin, Maple Leaf Rag | Oktaven, dichte Akkorde, Synkopen | 71–72 % | 72–97 % |
| Clara Schumann, Polonaise op. 1/1 | Triolen, Ottava, Schlüsselwechsel | 65–70 % | 74–79 % |
| „Lift Every Voice“ (nur Chor, Tenor im oktavierten Violinschlüssel) | kein Klaviersatz | 51–58 % | 95 % |
| **Durchschnitt (26 PDFs)** | | **83 %** | **86 %** |

Der Test läuft bei jedem Build in der CI und schlägt fehl, wenn der Durchschnitt unter 78 % (Noten)
bzw. 82 % (Rhythmus) fällt. Die Korpus-Erzeugung ist reproduzierbar: `src/test/resources/corpus/make_corpus.py`
und `truth.py`.

**Verlauf während der Entwicklung:** Start 69 % / 76 % → Schlüsselerkennung inkl. Wechsel → Tonart per
Formerkennung mit Abstimmung über alle Zeilen (26/26 richtig) → beliebige Taktarten + ganze Pausen →
Balken auf Notenlinien → Haltebögen → heute 83 % / 86 %.

## Grenzen

* Ausgelegt auf **sauber gesetzte** Noten. Gescannte Seiten funktionieren, wenn sie gerade und
  kontrastreich sind; schiefe Scans (> ~1°) und Handschrift werden kaum erkannt.
* Nicht unterstützt: Triolen und andere N-tolen, Vorschlagsnoten, Wiederholungszeichen/Volta (werden
  linear gelesen), Tonartwechsel, Ottava-Klammern, oktavierte Schlüssel, Arpeggien.
* Unterstützt seit dem Korpus-Test: Schlüsselwechsel (auch kleine Schlüssel), Haltebögen (Töne werden
  verlängert statt neu angeschlagen), Systeme mit Gesangs-/Chorzeilen (die unteren zwei Zeilen = Klavier),
  „C“-Taktangabe, beliebige Taktarten.
* Fehler lassen sich im Noten-Editor korrigieren. Für perfekte Ergebnisse: MusicXML aus MuseScore
  oder einem OMR-Programm am PC (z. B. Audiveris) importieren.
