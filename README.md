# Wattwerk

Android-App (für Tablets optimiert) zum Fahren von Trainings im ERG-Modus auf Smart-Trainern mit Bluetooth FTMS,
entwickelt für den **Van Rysel D100** und den **Wahoo Kickr Core 2**.

## Funktionen

- **Block-Editor:** Trainings aus Blöcken bauen (konstant, Rampe, Intervalle mit Wiederholungen), Leistung in % FTP oder Watt,
  Vorschau mit Zonenfarben, IF und TSS.
- **ERG-Modus:** Die App gibt dem Trainer für jeden Abschnitt die Zielleistung vor. Während der Fahrt: Pause, Überspringen,
  Intensität ±5 %, Countdown-Töne, Auto-Pause/Auto-Start, optionaler Schutz bei niedriger Trittfrequenz.
- **Live-Graphen:** Leistung (mit Ziel), Herzfrequenz und Trittfrequenz, letzte 10 Minuten oder ganze Fahrt.
- **Sensoren in den Einstellungen:** Smart-Trainer (FTMS), Pulsgurt, Trittfrequenzsensor (CSC) und Powermeter koppeln,
  mit Verbindungsstatus und Akkustand. Automatisches Wiederverbinden.
- **Vorlagen:** FTP-Rampentest, Sweet Spot, VO2max, Schwelle 2 × 20, Grundlage, Regeneration. Import und Export von Zwift-Dateien (.zwo).
- **Verlauf:** Auswertung mit NP, IF, TSS, kJ, W/kg und Wochensumme.
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
