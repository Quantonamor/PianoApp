# 🎹 PianoTiles – Klavier üben mit fallenden Noten

Android-App (Tablet & Handy, Querformat), die beim Klavierüben hilft: Noten fallen wie bei
„Piano Tiles“ auf eine Klaviatur. Die App **hört über das Mikrofon zu** und wartet, bis du die
richtigen Töne gespielt hast – oder läuft in frei wählbarem Tempo durch. Eigene Noten kommen per
**PDF-Import mit automatischer Notenerkennung** (oder MIDI/MusicXML) in deine private Bibliothek.

## Funktionen

| | |
|---|---|
| **Drei Modi** | *Warten* (Mikrofon, du bestimmst das Tempo) · *Fließend* (eingestelltes Tempo, Trefferquote) · *Anhören* (App spielt vor) |
| **Tempo** | 10 %–150 %, Schnellwahl 25/50/75/100/125 % |
| **Hände** | Links, rechts oder beide; die andere Hand wird auf Wunsch automatisch mitgespielt |
| **Übungsschleifen** | Takte durch Ziehen auf der Zeitleiste markieren, Doppeltipp = aktueller Takt, n× oder endlos, **Tempo-Treppe** (nach jedem guten Durchgang schneller), benannte Abschnitte speichern, „schwierige Takte“ aus der Fehlerstatistik |
| **Navigation** | Tippen = Start/Pause, senkrecht Wischen = Spulen, taktweise springen, Zeitleiste mit Fehler-Heatmap |
| **Klaviatur** | Zeigt zu spielende Tasten (Handfarbe), gehörte Töne (grüner Punkt), antippbar (auch ohne Klavier nutzbar) |
| **Import** | PDF (eigene Notenerkennung, offline), MIDI (.mid), MusicXML (.xml/.musicxml/.mxl); „Öffnen mit / Teilen“ aus anderen Apps |
| **Noten-Editor** | Piano-Roll zum Korrigieren: Tonhöhe, Oktave, Position, Länge, Hand, Einfügen, Löschen, Rückgängig |
| **Statistik** | Bestwert, Übungszeit, Fehler pro Takt |
| **Datenschutz** | Alles bleibt auf dem Gerät – keine Cloud, kein Konto |

## Installation

Die APK wird automatisch von GitHub Actions gebaut:

1. Auf GitHub im Repository auf **Actions → Build** gehen und den neuesten grünen Lauf öffnen.
2. Unten unter **Artifacts** `PianoTiles-apk` herunterladen und entpacken.
3. `app-release.apk` (oder `app-debug.apk`) aufs Gerät kopieren und installieren
   („Installation aus unbekannten Quellen“ erlauben).

Mindestens Android 8.0.

## Projektstruktur

```
core/   reines Kotlin (ohne Android): Datenmodell, MIDI/MusicXML-Import, Notenerkennung (OMR),
        Tonerkennung (FFT), Übungs-Engine – vollständig mit Unit-Tests
app/    Android-App (Jetpack Compose): Bibliothek, Übungsbildschirm, Import-Assistent, Editor, Einstellungen
docs/   Plan, Architektur, OMR-Details, Bedienungsanleitung, Changelog
```

Bauen: `./gradlew :core:test` (ohne Android-SDK möglich) · `./gradlew :app:assembleDebug` (mit SDK).

## Dokumentation

- [Bedienungsanleitung](docs/USER_GUIDE.md)
- [Projektplan & offene Punkte](docs/PLAN.md)
- [Architektur](docs/ARCHITECTURE.md)
- [PDF-Notenerkennung (OMR)](docs/OMR.md)
- [Changelog](docs/CHANGELOG.md)

## English

PianoTiles is an Android app (tablet & phone, landscape) for practicing the piano with falling notes.
It **listens through the microphone** and waits until you play the right notes (*Wait* mode), or runs at
a tempo of your choice (*Flow*), or plays the piece for you (*Listen*). Practice hands separately, loop
measures with a tempo ladder, and track your mistakes per measure. Your own pieces come in via **PDF import
with offline sheet music recognition** (or MIDI/MusicXML) and can be corrected in the built-in notes editor,
optionally with an AI fix (Claude, own API key). The UI is available in German and English: German devices
get German, all others English – changeable under Settings → "Sprache / Language". All data stays on the device.
