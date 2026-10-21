# Changelog

## 0.1.0 – erste Version

* Projektgerüst: `core` (Kotlin/JVM) + `app` (Android/Compose), GitHub-Actions-Build mit APK-Download.
* Core: Datenmodell, MIDI- und MusicXML-Import (inkl. .mxl), Demo-Stücke.
* Übungs-Engine: Warten/Fließend/Anhören, Hände, Tempo, Schleifen mit Wiederholungen und Tempo-Treppe,
  Metronom, Einzählen, Begleitung, Trefferquote, Fehler pro Takt.
* Polyphone Tonerkennung per FFT mit Anschlagserkennung.
* PDF-Notenerkennung (OMR) – auf drei echten Notensatz-Beispielen 100 % korrekt.
* App: Bibliothek, Übungsbildschirm (fallende Noten, Klaviatur, Zeitleiste), Import-Assistent mit
  Erkennungs-Vorschau, Noten-Editor, Einstellungen, Hilfe.
