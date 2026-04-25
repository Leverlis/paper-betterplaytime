# PlayerTime

**Spielzeit-Tracking Plugin für Paper mit MySQL-Anbindung**

https://i.imgur.com/HCBJrTa.png
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-brightgreen?style=flat-square&logo=minecraft)
![Paper](https://img.shields.io/badge/Paper-supported-blue?style=flat-square)

## Features

- Spielzeit jedes Spielers wird in einer **MySQL-Datenbank** gespeichert
- `/playtime` — eigene Spielzeit anzeigen
- `/playtime <Spieler>` — Spielzeit eines anderen Spielers anzeigen
- Rate-Limiter verhindert Spam
- Nachrichten vollständig konfigurierbar

## Permission

| Befehl                  | Beschreibung                        | Permission              |
|-------------------------|-------------------------------------|-------------------------|
| `/playtime`             | Eigene Spielzeit anzeigen           | –                       |
| `/playtime <Spieler>`   | Spielzeit eines anderen anzeigen    | `dev.playtime.other`    |


## Vorgang

1. **Spieler joint** — eine Session wird mit dem aktuellen Timestamp gestartet und im Speicher gehalten
2. **Spieler spielt** — alle 60 Sekunden werden alle aktiven Sessions in die Datenbank geschrieben (Zeit wird addiert)
3. **Spieler verlässt den Server** — die Session wird als beendet markiert und beim nächsten Flush weggeschrieben
4. **`/playtime`** — liest die gespeicherte DB-Zeit aus und addiert die aktuell laufende Session dazu, sodass die Anzeige immer aktuell ist
5. **Server stoppt** — alle noch offenen Sessions werden sofort in die DB geschrieben

> Reconnectet ein Spieler bevor der Flush läuft, wird die alte Session direkt weggeschrieben und eine neue gestartet.


## Architektur

```
│
├── command/
│   └── CommandPlaytime     — /playtime Command-Handler + Tab-Completion + Rate-Limiter
│
├── application/
│   └── PlaytimeService     — Session-Verwaltung, periodischer Flush, Spielzeit-Abfragen
│
├── domain/
│   └── PlayerPlaytime      — Datenklasse (playerName, totalPlaytime)
│
├── repository/
│   ├── PlaytimeRepository  — SQL-Zugriff (upsert, findById, findByName)
│   └── PlaytimeEntity      — Datenklasse aus der Datenbank
│
└── PlayerTime              — Plugin-Einstiegspunkt, DB-Verbindung, Initialisierung
```