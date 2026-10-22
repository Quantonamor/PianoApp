# Changelog

## 0.3.0 – in Arbeit (M6)

* **Realistische 3D-Hände**: echtes Hand-Modell mit Skelett (WebXR „generic-hand“, MIT), Software-Rasterizer mit
  Skinning, Tiefenpuffer und Licht; jeder Finger wird per inverser Kinematik auf seine Taste gesetzt, die Hand
  rückt so weit an die Tasten, dass Daumen und kleiner Finger hinreichen; der Unterarm blendet weich aus.
* **Klang mit echten Klavieraufnahmen** (npm `tonejs-instrument-piano-mp3`, MIT; jede dritte Taste, umgerechnet); Töne werden vorab
  mit kleiner Verzögerung eingeplant → gleichmäßiger Rhythmus, Akkorde exakt gleichzeitig.
* **MIDI-Keyboard** (USB/Bluetooth) als Eingang: exakt, mit Akkorden und Loslassen; Mikrofon wird dann nicht gebraucht.
* **Fünf neue Demo-Stücke**: Twinkle Twinkle, Hänschen klein, Jingle Bells, Happy Birthday, Menuett in G.
* **Begrüßungskarte** beim ersten Start, **Übungsstatistik** (heute / Woche / Tage in Folge) in der Bibliothek,
  **Mikrofon-Test** in den Einstellungen, aktiver Modus auf Handys beschriftet.
* Drehen mit dem Sensor (Hoch-/Querformat), Leisten blenden sich beim Spielen und nach 6 s ohne Berührung aus.
* `docs/USER_REVIEW.md`: 50 simulierte Übende gehen die App durch – Fragen, Vorschläge, Umsetzungsstand.

* **Stück-Pakete**: jedes Stück ist ein Ordner `files/library/<id>/` mit getrennten Teilen – `score.musicxml`
  (Noten, MusicXML 4.0), `tiles.json` (Übungsdaten), `hands.json` (Fingersatz), `original.*`/`scan-NN.jpg`,
  optional `sheet.pdf`, `manifest.json`. Austausch als `.pianotiles` (ZIP). Alte Bibliotheken werden automatisch
  übernommen; Sicherung = ZIP aus Paketen (alte Sicherungen lassen sich weiter laden).
* MusicXML-Export (Stimmen, Haltebögen, Triolen, Fingersatz, Tempo), Teilen als Paket/MusicXML/MIDI.
* Neue Importformate: ABC, MuseScore (.mscz/.mscx), MEI, RIFF-MIDI/.kar, Fotos (.jpg/.png).
* **Noten abfotografieren**: mehrere Fotos aufnehmen oder aus der Galerie wählen, Reihenfolge ändern; automatische
  Aufbereitung (Papier finden, Licht ausgleichen, gerade drehen, zuschneiden, skalieren).
* Bibliothek 2.0: Titelbild, ▶ Abspielen direkt auf der Karte, Fortschrittsring, Regale/Filter, Sortierung nach
  Schwierigkeit, „Weiter üben“, Mehrfachauswahl (teilen/löschen).
* **Akkorde & Halten**: Tasten eines Akkords müssen zusammen angeschlagen (innerhalb von 0,6 s) oder gemeinsam
  gehalten werden. Bleibt eine Taste gedrückt, deren Note vorbei ist, während eine andere weiterklingt, wird sie
  weiß mit Pfeil nach oben markiert („Loslassen: C4“); wird eine Note zu früh losgelassen, leuchtet ihre Taste grün
  umrandet („Halten: E4“, nur mit Bildschirm-Tasten, da das Mikrofon den Nachklang hört).
* **Hände**: halbtransparent und plastisch (Fingerglieder, Knöchel, Schatten); die Handflächen liegen unter der
  Klaviatur, sodass nur die Finger auf den Tasten liegen.
* **Neue Optik nach Vorlagen des Nutzers**: schwarzer Hintergrund, abgerundete leuchtende Notenbalken mit
  Notennamen unten im Balken, leuchtende Trefferlinie mit Funken, plastische Klaviatur mit rotem Filzstreifen und
  Oktav-Beschriftung (C2, C3 …); linke Hand blau-violett, rechte Hand orange. Beim Spielen verschwinden die
  Leisten (kleine Zurück-/Pause-Knöpfe bleiben).
* **Hoch- und Querformat**: die App dreht sich mit dem Gerät; im Hochformat zweizeilige Kopfzeile, Notenblatt über
  den fallenden Noten, höhere Tasten; Bibliothek und Editor passen sich schmalen Bildschirmen an.
* **Klang neu**: klavierähnliche Töne (vorberechnet je Tonhöhe: Teiltöne mit Inharmonizität, zweistufiges
  Abklingen, Saitenpaar, Hammeranschlag); Akkorde erklingen gleichzeitig, bis zu 48 Töne ohne Aussetzer, weiches
  Dämpfen. Im Modus „Fließend“ ohne Mikrofon erklingt auch die geübte Hand.
* **Aufgeräumter Übungsbildschirm**: Menü „Ansicht“ (Auge) für Hände, Fingersatz, Notennamen, Taktnummern,
  Metronom; kompakte Kopfzeile auf Handys; Hinweise kleiner bzw. nur beim ersten Start; Hände, Fingersatz und
  Tastennamen sind anfangs ausgeblendet.
* **Notenblatt für jedes Stück**: Stücke ohne Original (MIDI, MusicXML, ABC, MuseScore, MEI, Demos) bekommen ein
  selbst gesetztes Notenblatt (`sheet.pdf`, mit Fingersatz und Takt-Positionen) neben den fallenden Noten.
* PDF-Erkennung: Kreuz der Tonart, das beim Entfernen der Notenlinien in zwei Hälften zerfällt, wird erkannt.
* **3D-Hände**: gezeichnete, schattierte Hände über der Klaviatur zeigen, welcher Finger welche Taste spielt
  (Schalter ✋ im Übungsbildschirm); Fingerzahlen auf den fallenden Noten. Automatischer Fingersatz für alle Stücke
  ohne Fingersatz (dynamische Programmierung nach Parncutt), Fingersätze aus Dateien bleiben erhalten.

## 0.2.0

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
* Englische Oberfläche (Gerätesprache oder Einstellung „Sprache / Language“).
* CI komplett grün: Core-Tests, APK-Build und Emulator-Bedientest.

## 0.1.0 – erste Version

* Projektgerüst: `core` (Kotlin/JVM) + `app` (Android/Compose), GitHub-Actions-Build mit APK-Download.
* Core: Datenmodell, MIDI- und MusicXML-Import (inkl. .mxl), Demo-Stücke.
* Übungs-Engine: Warten/Fließend/Anhören, Hände, Tempo, Schleifen mit Wiederholungen und Tempo-Treppe,
  Metronom, Einzählen, Begleitung, Trefferquote, Fehler pro Takt.
* Polyphone Tonerkennung per FFT mit Anschlagserkennung.
* PDF-Notenerkennung (OMR) – auf drei echten Notensatz-Beispielen 100 % korrekt.
* App: Bibliothek, Übungsbildschirm (fallende Noten, Klaviatur, Zeitleiste), Import-Assistent mit
  Erkennungs-Vorschau, Noten-Editor, Einstellungen, Hilfe.
