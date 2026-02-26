# 🚆 DB Pendler Wecker

Eine Android App mit Home-Screen Widget für Pendler, die Zugverbindungen der Deutschen Bahn anzeigt und Wecker für die Abfahrt stellen kann.

## Features

- 🔍 **Bahnhof-Suche**: Automatische Vervollständigung bei der Eingabe
- 🔄 **Swap-Button**: Schnelles Tauschen von Start und Ziel (perfekt für Pendler)
- 📱 **Home-Screen Widget** (4x3): Zeigt die nächsten 3 Verbindungen
- ⏰ **Wecker-Funktion**: Stellt automatisch einen Wecker X Minuten vor Abfahrt
- 🔔 **Benachrichtigungen**: Alarm mit Ton und Vibration

## Screenshots

(TODO: Screenshots einfügen)

## Installation

### Option 1: APK Download (empfohlen)

1. Gehe zu [Releases](../../releases)
2. Lade die neueste `app-debug.apk` herunter
3. Installiere die APK auf deinem Android-Gerät

### Option 2: Selbst bauen

1. Klone das Repository:
   ```bash
   git clone https://github.com/DEIN_USERNAME/db-pendler-wecker.git
   cd db-pendler-wecker
   ```

2. Baue die APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. Die APK befindet sich unter `app/build/outputs/apk/debug/app-debug.apk`

## Verwendung

### Ersteinrichtung

1. Öffne die App
2. Gib deinen Start-Bahnhof ein (z.B. "Düsseldorf Hbf")
3. Gib deinen Ziel-Bahnhof ein (z.B. "Köln Hbf")
4. Die Bahnhöfe werden gespeichert

### Widget hinzufügen

1. Lange auf den Home-Screen drücken
2. "Widgets" auswählen
3. "DB Pendler" Widget suchen
4. Auf den Home-Screen ziehen

### Wecker stellen

1. Im Widget oder in der App werden die nächsten Verbindungen angezeigt
2. Tippe auf "⏰-10" für Wecker 10 Minuten vor Abfahrt
3. Tippe auf "⏰-15" für Wecker 15 Minuten vor Abfahrt
4. Der Wecker wird automatisch gestellt

### Bahnhöfe tauschen

Tippe auf den 🔄 Button um Start und Ziel zu tauschen - perfekt für den Heimweg!

## Technische Details

- **Sprache**: Kotlin
- **Min SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 15 (API 35)
- **API**: [transport.rest](https://v6.db.transport.rest) (Hafas-Wrapper für DB-Daten)

## Berechtigungen

- `INTERNET`: Für API-Abfragen
- `SCHEDULE_EXACT_ALARM`: Für präzise Wecker
- `POST_NOTIFICATIONS`: Für Alarm-Benachrichtigungen
- `VIBRATE`: Für Vibration bei Alarm
- `RECEIVE_BOOT_COMPLETED`: Zum Wiederherstellen von Weckern nach Neustart

## Lizenz

MIT License - siehe [LICENSE](LICENSE)

## Beiträge

Pull Requests sind willkommen! Bitte erstelle zuerst ein Issue für größere Änderungen.

## Autor

Erstellt für Gingerbeard.3D
