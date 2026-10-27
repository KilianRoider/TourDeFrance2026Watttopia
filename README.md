# Wattwerk

Android-App (für Tablets optimiert) zum Fahren von Trainings im ERG-Modus auf Smart-Trainern mit Bluetooth FTMS,
entwickelt für den **Van Rysel D100** und den **Wahoo Kickr Core 2**.

## Funktionen

- **Block-Editor:** Trainings aus Blöcken bauen (konstant, Rampe, Intervalle mit Wiederholungen, **frei ohne ERG**),
  Leistung in % FTP oder Watt, Trittfrequenz-Ziele, mehrere Hinweistexte mit Zeitpunkt pro Block, Kennzeichnung als
  FTP-Test, Vorschau mit Zonenfarben, IF und TSS.
- **ERG-Modus:** Die App gibt dem Trainer für jeden Abschnitt die Zielleistung vor; das nächste Ziel wird 2 s früher
  gesendet. Während der Fahrt: Pause, Abschnitt zurück/überspringen, **+1 min / +10 s verlängern**, Intensität in
  1-%-Schritten (plus ±5/±10 und Reset), **ERG an/aus** mit simulierter Steigung, „Ich bin platt“ beim Rampentest,
  **Weiterfahren** nach dem Ende, Countdown-Overlay 3-2-1 und „Gleich: …“-Vorschau.
- **Spirale des Todes:** Fällt die Trittfrequenz unter die Schwelle, nimmt die App das Ziel auf 40 % zurück und führt
  es nach dem Antreten in 3 s wieder hoch (oder sofort per Tipp). **PowerMatch** gleicht Trainer und Powermeter ab.
- **Datenseiten wie am Garmin Edge:** 8 feste Raster (1 Feld bis 3 × 4), bis zu 6 Seiten zum Wischen, getrennt für
  Training und freie Fahrt, Schriftgröße S–XL. 50 Felder in sechs Gruppen (Leistung 3 s/10 s/30 s, NP, IF, TSS, W/kg,
  W'-Bilanz, Pulszone, Abschnitt verbleibend, nächster Abschnitt, Uhrzeit, ERG-Status, …).
- **Live-Graphen:** Leistung (mit Ziel), Herzfrequenz, Trittfrequenz und W'-Bilanz, letzte 10 Minuten oder ganze Fahrt.
- **Töne:** Countdown, Abschnittswechsel (heller wenn härter), Trittfrequenz-Hinweise, Metronom im Takt der
  Ziel-Trittfrequenz, Lautstärke und jede Art einzeln abschaltbar.
- **Auswertung:** Sterne pro Abschnitt mit wählbarer Toleranz (locker/normal/streng; Rampen, freie Blöcke und kurze
  Abschnitte unbewertet, die ersten 5 s zählen nicht), Bestwerte 5 s/1/5/20 min mit Rekorden der letzten 365 Tage,
  Vergleich mit dem letzten gleichen Training, RPE und Notiz, Zeit in Zonen (Coggan 7 oder Zwift 6), FTP-Vorschlag
  nach Rampentest (75 % der besten Minute) oder 20-Minuten-Test (95 %), FTP-Verlauf, Wochenziel und Wochen-Serie.
- **Bibliothek:** 18 Vorlagen (Rampentest, Rampentest Lite, 20-Minuten-Test, Sweet Spot, Schwelle, Over-Under, VO2max,
  30/30, Anaerob, Sprints, Pyramide, Tempo, Trittfrequenz-Drills, Grundlage, Regeneration), Suche, Sortieren, Filter
  nach Art und Dauer, Favoriten. Import von Zwift (.zwo) und TrainerRoad/ErgVideo (.mrc, .erg), Export als .zwo.
- **Sensoren in den Einstellungen:** Smart-Trainer (FTMS), Pulsgurt, Trittfrequenzsensor (CSC) und Powermeter koppeln,
  mit Verbindungsstatus und Akkustand. Automatisches Wiederverbinden.
- **Export:** FIT-Datei (geprüft mit Garmins offiziellem FIT-Decoder), Upload zu Strava, Teilen für Garmin Connect.

## Installation

Jeder Push auf `main` baut die App über GitHub Actions. Die fertige APK liegt unter
**Releases → latest → `Wattwerk.apk`**. Auf dem Tablet öffnen und die Installation aus dieser Quelle erlauben.
Spätere Builds lassen sich direkt darüber installieren, die Daten bleiben erhalten.

## Strava einrichten

1. Auf [strava.com/settings/api](https://www.strava.com/settings/api) eine eigene API-Anwendung anlegen
   (seit Juni 2026 ist dafür ein Strava-Abo nötig). Als „Authorization Callback Domain“ `localhost` eintragen.
2. In der App unter Einstellungen → Strava Client-ID und Client-Secret eintragen und „Mit Strava verbinden“ tippen.
3. Ab dann wird jede gespeicherte Fahrt automatisch hochgeladen (abschaltbar).

## Garmin Connect

Garmin nimmt derzeit keine neuen Apps in sein Connect-Entwicklerprogramm auf. Deshalb: Fahrt öffnen →
„FIT-Datei teilen“ → auf [connect.garmin.com](https://connect.garmin.com) über „Daten importieren“ hochladen.

## Aufbau

| Modul | Inhalt |
| --- | --- |
| `core` | Reines Kotlin ohne Android: Trainingsmodell, Workout-Engine, FTMS/HR/CSC/CPS-Parser, FIT-Encoder, ZWO, Statistik. Mit Unit-Tests. |
| `app` | Android-App mit Jetpack Compose: Bluetooth-Verbindungen, Vordergrunddienst, Oberfläche, Strava. |

Bauen: `./gradlew :core:test :app:assembleDebug`
