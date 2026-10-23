# Changelog

## 0.3.0 – in Arbeit (M6)

* **PDF-Erkennung**: eine Note mit Hals wird nicht mehr für eine Taktangabe gehalten (fehlende Köpfe in Chorälen,
  Auftakte); ein zerfallenes „C“ (4/4) wird erkannt, Fähnchen gelten nicht mehr als Ganze Noten;
  Regressions-Satz 90,0 % Noten / 93,6 % Präzision / 92,1 % Rhythmus (vorher 89,2 / 93,0 / 91,1).
* **Hautfalten** an den Fingergelenken.
* **Natürlichere Hände**: der Unterarm kommt aus der Körpermitte (Mitte der Klaviatur, nicht Bildschirmmitte –
  beim Zoomen drehte sich die Hand sonst zu stark); rückt die Hand für eine schwarze Taste vor, liegen die freien
  Finger weiter innen auf den Tasten, statt sich zu Stummeln einzurollen.
* **Schärfere Hände**: gerendert mit 50 % statt 40 % Auflösung (eigener Thread, ~5 ms pro Bild).
* **Klang**: der Audio-Thread läuft höchstens 0,2 s der Echtzeit voraus (eine nicht blockierende Ausgabe ließ ihn sonst durchdrehen).
* **Haltepedal**: aus MIDI (Controller 64), MusicXML („Ped.“-Zeichen, auch Pedalwechsel) und MuseScore übernommen; beim
  Abspielen klingen losgelassene Töne bis zum Lösen des Pedals weiter; grüner Streifen „Ped.“ am rechten Rand
  der fallenden Noten; MIDI-Export schreibt das Pedal mit (`PedalTest`).
* **Dynamik aus MusicXML und MuseScore** (pp … ff, sf/sfz als Akzent, `sound dynamics`, MuseScore-Velocity) wird zur Anschlagstärke – mit den drei
  Anschlagstufen des Flügels klingt das Vorspiel lebendig statt gleichförmig.
* **Lernpfad**: 13 Lektionen vom ersten Ton (fünf Finger je Hand) über Kinderlieder und beide Hände bis „Für Elise“;
  jede Lektion stellt Hand, Modus und Tempo ein, 1–3 Sterne je nach Treffsicherheit, „Nächste Lektion“ im
  Abschluss-Dialog. Karte oben in der Bibliothek, „Los geht's“ der Begrüßung öffnet ihn (`core/practice/Course.kt`).
* PDF-Erkennung: Alla-breve-Zeichen (¢) ergibt 2/2, eine „6“ als obere Taktziffer 6/8.

* **Hände überarbeitet** (Nahaufnahmen in `HandRenderTest`): vollere Finger (16–20 mm wie echte), glatte
  Oberfläche (Unterteilung), Fingernägel, Kontaktschatten zwischen den Fingern, matte Haut statt Plastikglanz,
  weichere Kanten; natürliche Haltung (Mittelfinger am weitesten auf der Taste, Daumen vorne, keine Krallen-
  Finger); der spielende Finger leuchtet in der Farbe seiner Hand. Gerendert in einem eigenen Thread, damit der Bildschirm flüssig bleibt. Fingersatz: weite Akkorde
  mit Daumen und kleinem Finger (Ode links C+G jetzt 5–1), Umsetzen der Hand bei langen Noten erlaubt.
* **Realistische 3D-Hände**: echtes Hand-Modell mit Skelett (WebXR „generic-hand“, MIT), Software-Rasterizer mit
  Skinning, Tiefenpuffer und Licht; jeder Finger wird per inverser Kinematik auf seine Taste gesetzt, die Hand
  rückt so weit an die Tasten, dass Daumen und kleiner Finger hinreichen; der Unterarm blendet weich aus.
* **Klang neu: Salamander-Konzertflügel** (Alexander Holm, CC BY 3.0) in drei Anschlagstärken (leise/mittel/laut,
  jede dritte Taste); die Tonhöhe wird beim Abspielen umgerechnet. Raumhall und ein weicher Kompressor statt
  Übersteuerung bei Akkorden.
* **Fehler beim Abspielen behoben**: Eingeplante Töne belegten schon Stimmen – bei mehr als 64 vorausgeplanten
  Tönen (Editor: zwei Takte auf einmal) gingen Töne verloren. Ein wiederholter Ton wurde bis zu 0,12 s zu früh
  abgedämpft. Die ersten Töne nach „Start“ kamen einen Frame zu spät. `PianoSoundTest` spielt alle Demos wie die
  App ab: jeder Ton genau einmal, Abweichung ≤ 4 ms, keine Übersteuerung.
* **MIDI-Keyboard** (USB/Bluetooth) als Eingang: exakt, mit Akkorden und Loslassen; Mikrofon wird dann nicht gebraucht.
* **Fünf neue Demo-Stücke**: Twinkle Twinkle, Hänschen klein, Jingle Bells, Happy Birthday, Menuett in G.
* **Begrüßungskarte** beim ersten Start, **Übungsstatistik** (heute / Woche / Tage in Folge) in der Bibliothek,
  **Mikrofon-Test** in den Einstellungen, aktiver Modus auf Handys beschriftet.
* Drehen mit dem Sensor (Hoch-/Querformat), Leisten blenden sich beim Spielen und nach 6 s ohne Berührung aus.
* PDF-Erkennung: oktavierter Violinschlüssel (Tenor) auch bei verschmolzener bzw. dünner 8 (Regression 87,6 → 89,3 % Noten).
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
