# Changelog

## 0.2.0 – in Arbeit

* PDF-Erkennung stark verbessert (fester Testkorpus 69 % → 83 % Noten): Schlüssel inkl. Wechsel, Tonart per
  Formerkennung mit Abstimmung, Balken auf Notenlinien, beliebige Taktarten, ganze Pausen, Haltebögen,
  Triolen, verbundene Taktziffern, Vorzeichen über ganze Zeichen.
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

## 0.1.0 – erste Version

* Projektgerüst: `core` (Kotlin/JVM) + `app` (Android/Compose), GitHub-Actions-Build mit APK-Download.
* Core: Datenmodell, MIDI- und MusicXML-Import (inkl. .mxl), Demo-Stücke.
* Übungs-Engine: Warten/Fließend/Anhören, Hände, Tempo, Schleifen mit Wiederholungen und Tempo-Treppe,
  Metronom, Einzählen, Begleitung, Trefferquote, Fehler pro Takt.
* Polyphone Tonerkennung per FFT mit Anschlagserkennung.
* PDF-Notenerkennung (OMR) – auf drei echten Notensatz-Beispielen 100 % korrekt.
* App: Bibliothek, Übungsbildschirm (fallende Noten, Klaviatur, Zeitleiste), Import-Assistent mit
  Erkennungs-Vorschau, Noten-Editor, Einstellungen, Hilfe.
