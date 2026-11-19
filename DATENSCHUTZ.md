# Datenschutz – Minimal Player

**Kurz:** Minimal Player erhebt keine personenbezogenen Daten, hat keinen Internetzugriff und gibt nichts an Dritte weiter. Alles bleibt auf deinem Handy.

## Was auf dem Handy gespeichert wird

Lokal im App-Speicher, nur für die Funktion der App:

- die Ordner, die du für Musik und Hörbücher ausgewählt hast
- ein Verzeichnis der Audiodateien darin (Dateiname, Ordnerpfad) für schnelle Ordneransicht und Suche
- gemerkte Stellen in Hörbüchern (Datei, Minute/Sekunde)
- deine Einstellungen (Tempo, Schlaf-Timer, Schrift, Sternenhimmel …)

Nichts davon verlässt das Gerät. Die Android-Gerätesicherung ist für diese App abgeschaltet. Deinstallieren oder „Speicher löschen“ entfernt alles.

## Internet

Keines. Die Berechtigung `INTERNET` fehlt – auch eingebundene Bibliotheken bekommen sie nicht. Kein Analyse-SDK, keine Absturzberichte, keine Werbung, kein Konto.

## Berechtigungen

| Berechtigung | Wofür | Nachfrage? |
|---|---|---|
| Ordnerzugriff (Android-Speicherzugriff, von dir gewählt) | Nur die Ordner lesen, die du selbst auswählst. Kein Zugriff auf andere Dateien. | Du wählst jeden Ordner selbst |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Wiedergabe läuft weiter, wenn die App im Hintergrund oder der Bildschirm aus ist; Steuerung auf Sperrbildschirm und per Kopfhörer | nein (automatisch) |
| `WAKE_LOCK` | Das Handy unterbricht die Wiedergabe bei ausgeschaltetem Bildschirm nicht | nein (automatisch) |
| `MODIFY_AUDIO_SETTINGS` | Equalizer und Lautstärke über 100 % – wirkt nur auf die Wiedergabe dieser App | nein (automatisch) |
| `VIBRATE` | Kurzes Vibrieren als Bestätigung, wenn Schütteln den Schlaf-Timer neu startet | nein (automatisch) |
| Bewegungssensor (Beschleunigung) | Nur während der Schlaf-Timer läuft, um ein Schütteln zu erkennen. Werte werden nicht gespeichert. | keine Berechtigung nötig |

Kein Zugriff auf Kontakte, Standort, Kamera, Mikrofon, Telefon, SMS oder Benachrichtigungen anderer Apps.

## Verantwortlich

Philip Decker, detec, Nideggen – Kontakt über https://philipdecker.de
