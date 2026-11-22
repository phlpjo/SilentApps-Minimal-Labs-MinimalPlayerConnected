# Datenschutz – Minimal Player Connected

**Kurz:** Minimal Player Connected erhebt keine personenbezogenen Daten, spricht mit keinem Server im Internet und gibt nichts an Dritte weiter. Nur wenn du selbst einen Lautsprecher in deinem WLAN auswählst, holt dieser Lautsprecher den gerade gespielten Titel direkt vom Handy ab.

## Was auf dem Handy gespeichert wird

Lokal im App-Speicher, nur für die Funktion der App:

- die Ordner, die du für Musik und Hörbücher ausgewählt hast
- ein Verzeichnis der Audiodateien darin (Dateiname, Ordnerpfad) für schnelle Ordneransicht und Suche
- gemerkte Stellen in Hörbüchern (Datei, Minute/Sekunde)
- deine Einstellungen (Tempo, Schlaf-Timer, Schrift, Sternenhimmel …)

Nichts davon wird hochgeladen oder an Dritte gegeben. Die Android-Gerätesicherung ist für diese App abgeschaltet. Deinstallieren oder „Speicher löschen“ entfernt alles.

## Netzwerk – nur dein Heimnetz

Die App verbindet sich ausschließlich mit Geräten in deinem eigenen WLAN, nie mit Servern im Internet. Kein Analyse-SDK, keine Absturzberichte, keine Werbung, kein Konto, keine Update-Abfrage.

- **Suche:** Öffnest du „Wiedergabe auf“, schickt die App eine UPnP-Suchanfrage (SSDP) ins lokale Netz. Lautsprecher antworten mit ihrer Adresse; die App liest deren Gerätebeschreibung (Name, Modell). Nichts davon wird gespeichert.
- **Abspielen:** Wählst du einen Lautsprecher, startet die App einen kleinen Server im Handy. Der Lautsprecher bekommt eine zufällige, nicht erratbare Adresse für den gerade gespielten Titel (und ggf. das Cover) und holt die Datei dort ab. Es gibt kein Verzeichnis, keine Liste deiner Dateien. Der Server läuft nur, solange ein Lautsprecher verbunden ist.
- **Minimal Library (dein Computer):** Im Bereich „PC“ sucht die App Minimal Library im WLAN und verbindet sich nach einmaligem Koppeln (6-stelliger Code) direkt mit deinem Computer – nie mit einem Server im Internet. Sie lädt Titelliste und Playlisten, spielt Titel von dort ab, speichert auf Wunsch Kopien im eigenen App-Speicher („offline verfügbar“) und überträgt Musik aus deinen Handy-Ordnern, die auf dem Computer noch fehlt, sowie deine Playlisten dorthin. Entkoppeln jederzeit in den Einstellungen.
- **Steuerung:** Play, Pause, Spulen, Lautstärke gehen per UPnP direkt an den Lautsprecher.
- Was der Lautsprecher bzw. dessen Hersteller mit den abgespielten Titeln macht (z. B. Verlauf in dessen App), liegt außerhalb dieser App.

## Berechtigungen

| Berechtigung | Wofür | Nachfrage? |
|---|---|---|
| Ordnerzugriff (Android-Speicherzugriff, von dir gewählt) | Nur die Ordner lesen, die du selbst auswählst; beim Export auf einen USB-Stick in den dort gewählten Ordner schreiben. Kein Zugriff auf andere Dateien. | Du wählst jeden Ordner selbst |
| `INTERNET` | Verbindung zu Lautsprechern im eigenen WLAN (Android verlangt diese Berechtigung auch für reine Heimnetz-Verbindungen). Keine Verbindung ins Internet. | nein (automatisch) |
| `CHANGE_WIFI_MULTICAST_STATE` | Lautsprecher im WLAN finden (Suchanfrage per Multicast), nur während der Suche | nein (automatisch) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Wiedergabe läuft weiter, wenn die App im Hintergrund oder der Bildschirm aus ist; Steuerung auf Sperrbildschirm und per Kopfhörer | nein (automatisch) |
| `WAKE_LOCK` | Das Handy unterbricht die Wiedergabe bei ausgeschaltetem Bildschirm nicht; beim Streamen bleiben WLAN und Prozessor wach, damit der Lautsprecher die Datei weiter abholen kann | nein (automatisch) |
| `MODIFY_AUDIO_SETTINGS` | Equalizer und Lautstärke über 100 % – wirkt nur auf die Wiedergabe dieser App | nein (automatisch) |
| `VIBRATE` | Kurzes Vibrieren als Bestätigung, wenn Schütteln den Schlaf-Timer neu startet | nein (automatisch) |
| Bewegungssensor (Beschleunigung) | Nur während der Schlaf-Timer läuft, um ein Schütteln zu erkennen. Werte werden nicht gespeichert. | keine Berechtigung nötig |

Kein Zugriff auf Kontakte, Netzwerkstatus, Standort, Kamera, Mikrofon, Telefon, SMS oder Benachrichtigungen anderer Apps.

## Verantwortlich

Philip Decker, detec, Nideggen – Kontakt über https://philipdecker.de

## Hinweis für spätere Versionen

Ab Ziel-API 37 (Android 17) verlangt Android für Verbindungen ins lokale Netz die Laufzeit-Berechtigung `ACCESS_LOCAL_NETWORK`. Bei Ziel-API 36 ist sie über `INTERNET` automatisch erteilt. Beim Umstieg auf 37 muss die App vor der ersten Suche danach fragen.

## Abgleich zwischen Minimal Player und Minimal Player Connected

Sind beide Apps installiert, gleichen sie Ordnerliste und Playlisten direkt auf dem Gerät miteinander ab (Android-ContentProvider, nur die jeweils andere App darf abfragen). Es wird nichts ins Netz übertragen. Jede App behält ihren eigenen vollständigen Stand; wird eine deinstalliert, bleibt in der anderen alles erhalten.
