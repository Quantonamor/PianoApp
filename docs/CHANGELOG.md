# Changelog

## 0.2.0 – in Arbeit

* PDF-Erkennung stark verbessert (fester Testkorpus 69 % → 83 % Noten): Schlüssel inkl. Wechsel, Tonart per
  Formerkennung mit Abstimmung, Balken auf Notenlinien, beliebige Taktarten, ganze Pausen, Haltebögen,
  Triolen, verbundene Taktziffern, Vorzeichen über ganze Zeichen.
* PDF-Erkennung, Konvergenz-Schleife (Session 2): Runden 1 und 2 vollständig erkannt; Regressions-Satz
  94,9 % Noten / 97,4 % Rhythmus (Zweistimmigkeit, Haltebögen über Taktstriche, Vorzeichen an Sekunden,
  Fermaten, gebrochene Taktziffern).
* PDF-Erkennung, Konvergenz-Schleife (Session 3): Runden 4–7 vollständig erkannt (frisch 5/10, 5/10, 6/10,
  3/10 → nach Korrekturen je 10/10); Regressions-Satz 96 PDFs bei 98,2 % Noten / 99,1 % Rhythmus. Neu: Triolen
  im Auftakt (auch ohne gefundene „3“), Prima Volta ergänzt den Auftakt, Systeme mit mehreren Strophen Liedtext,
  dünne ♭ und ♭ am Balken, Fähnchen über Systemgrenzen, Liedtext unter Hälsen, sehr kurze Haltebögen.
* Testkorpus mit 14 Stücken × 2 Notensatz-Programmen als Regressionstest in der CI; Konvergenz-Werkzeuge in `tools/omr-eval`.
* Emulator-Bedientest in der CI, robustere Audio-Threads.
* **Noten-Editor 2.0**: oben das Original-PDF mit Taktmarkierung, unten der Piano-Roll; Takt-Navigation,
  Liste „Bitte prüfen“ (geschätzter Rhythmus, nicht gefüllte Takte, große Sprünge), Noten ziehen (Tonhöhe/Zeit,
  rechter Rand = Länge), ♯/♭, Notenwerte, Takt-Werkzeuge (kopieren/einfügen, einfügen/löschen, leeren,
  Hände tauschen, transponieren, Taktart), Rückgängig **und** Wiederholen, „Ab Takt anhören“.
* **KI-nachbessern**: ein Knopf im Editor; Claude vergleicht jede PDF-Seite mit den erkannten Noten und schlägt
  Korrekturen je Takt/Hand vor (Fortschritt, Live-Protokoll, Vorher/Nachher, Vorschau, einzeln/alle übernehmen,
  rückgängig). Eigener API-Schlüssel in den Einstellungen, verschlüsselt gespeichert.
* Originales Notenblatt neben den fallenden Noten beim Üben (Taktmarkierung, Antippen = springen).
* MusicXML: Wiederholungen/Volta ausgeklappt, Tempowechsel (auch MIDI).
* Bibliothek sichern/wiederherstellen (ZIP), Stück als MIDI teilen.
* Englische Oberfläche (Gerätesprache oder Einstellung „Sprache / Language“).
* CI komplett grün: Core-Tests, APK-Build und Emulator-Bedientest.
* Erster Test auf dem (lokalen) Emulator, behoben: Überschrift/Icons der Bibliothek waren schwarz auf dunklem
  Grund; fallende Noten wurden über die obere Leiste gezeichnet; Notennamen auf den Kacheln und Tasten-
  beschriftungen wurden abgeschnitten (jetzt mittig und passend verkleinert); „Bitte prüfen“ meldet Pausen
  nicht mehr als Fehler, wenn sie im Stück üblich sind (z. B. linke Hand bei „Für Elise“).
* Notennamen folgen der Tonart: in B-Tonarten E♭/Es statt D#/Dis (Tonart wird aus den Tönen geschätzt, gilt
  auch für schon gespeicherte Stücke); kurze Kacheln werden dank engerer Schrift öfter beschriftet.
* Dialoge „Übungsschleife“, „Tempo“ und „Geschafft“ waren auf Handys im Querformat zu schmal (Überlappungen,
  „125 %“ unerreichbar) – jetzt bis 560/640 dp breit; das Ergebnis ist je nach Trefferquote grün/orange/rot.
* Absturz behoben: Beim Öffnen einer Datei per „Teilen → PianoTiles“ konnte das gerade importierte Stück
  verschwinden (zweites App-Fenster lud die Bibliothek gleichzeitig neu) – Laden/Speichern sind jetzt serialisiert.
* PDF-Erkennung (Konvergenz-Schleife, Regressions-Satz 87,6/91,1/89,5 % → 94,2/94,3/96,4 % Noten/Präzision/
  Rhythmus): Haltebögen über Taktstriche und Zeilenumbrüche; Rhythmus je Stimme bei zwei Stimmen pro Zeile;
  kurze letzte Notenzeile wird gefunden; Lücke zwischen zwei Balken ist kein hohler Notenkopf; Auftakte und unvollständige Takte mitten im Stück (vor Wiederholungen) behalten ihre Länge;
  ♮/♯ werden nicht mehr als „Loch“ einer halben Note gefüllt (verschmolzen sonst mit dem Notenkopf);
  Balkenzählung stolpert nicht mehr über Köpfe der anderen Stimme; halbe Noten mit gemeinsamem Hals;
  Vorzeichen gehören zum nächsten Kopf rechts; Verovio-„C“ (4/4), das beim Entfernen der Notenlinien
  zerfällt; ein Akkord mit Hals wird nicht mehr für eine Taktangabe gehalten.
* Auswerte-Werkzeuge laufen auch unter Windows (Git Bash); Lösungen berücksichtigen Auftakte
  (`tools/omr-eval/truthlib.py`, `rescore.py`).

## 0.1.0 – erste Version

* Projektgerüst: `core` (Kotlin/JVM) + `app` (Android/Compose), GitHub-Actions-Build mit APK-Download.
* Core: Datenmodell, MIDI- und MusicXML-Import (inkl. .mxl), Demo-Stücke.
* Übungs-Engine: Warten/Fließend/Anhören, Hände, Tempo, Schleifen mit Wiederholungen und Tempo-Treppe,
  Metronom, Einzählen, Begleitung, Trefferquote, Fehler pro Takt.
* Polyphone Tonerkennung per FFT mit Anschlagserkennung.
* PDF-Notenerkennung (OMR) – auf drei echten Notensatz-Beispielen 100 % korrekt.
* App: Bibliothek, Übungsbildschirm (fallende Noten, Klaviatur, Zeitleiste), Import-Assistent mit
  Erkennungs-Vorschau, Noten-Editor, Einstellungen, Hilfe.
