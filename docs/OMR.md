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

`OmrRealScoreTest` erkennt drei mit LilyPond gesetzte Stücke und vergleicht mit dem MIDI derselben Quelle:

| Stück | Noten | Erkannt (Tonhöhe + Zeitpunkt) | Fehlalarme |
|---|---|---|---|
| Ode an die Freude (C-Dur, 4/4, ganze/halbe Noten, Akkorde) | 101 | 100 % | 0 |
| Menuett G-Dur (Petzold, 3/4, Achtel mit Balken, punktierte Halbe) | 94 | 100 % | 0 |
| Kleine Etüde (D-Dur, Sechzehntel, Pause, Auflösungszeichen) | 58 | 100 % | 0 |

## Grenzen

* Ausgelegt auf **sauber gesetzte** Noten. Gescannte Seiten funktionieren, wenn sie gerade und
  kontrastreich sind; schiefe Scans (> ~1°) und Handschrift werden kaum erkannt.
* Nicht unterstützt: Triolen, Vorschlagsnoten, Wiederholungszeichen/Volta (werden linear gelesen),
  Schlüsselwechsel mitten im Stück, Tonartwechsel, Haltebögen (Töne werden neu angeschlagen),
  mehr als zwei Notenzeilen pro System (nur die unteren zwei = Klavier).
* Fehler lassen sich im Noten-Editor korrigieren. Für perfekte Ergebnisse: MusicXML aus MuseScore
  oder einem OMR-Programm am PC (z. B. Audiveris) importieren.
