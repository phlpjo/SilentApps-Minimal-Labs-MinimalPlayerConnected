# Minimal Player Connected

Minimal Player plus Streaming an Lautsprecher im Heimnetz (Sonos, UPnP/DLNA). Im Stil von Minimal. Bezahl-App für Google Play – **privates Repo, keine Open-Source-Lizenz.**

- Hauptumschalter Musik / Hörbücher, je eigene Ordner (mehrere)
- Echte Ordneransicht statt ID3-Tags, natürliche Sortierung, Sofortsuche (Room-FTS-Index)
- Lückenlose Wiedergabe (ExoPlayer-Playlist)
- Hörbücher: Stelle je Ordner und je Datei, Tempo 1,0–2,5× mit Tonhöhenkorrektur, Schlaf-Timer mit Ausblenden und Schütteln, Zurück-/Vorspringen
- Sperrbildschirm, Benachrichtigung, Bluetooth-/Kopfhörer-Tasten (Media3-Session)
- **Wiedergabe auf:** Symbol im Player → Handy, Sonos-Gruppe oder UPnP/DLNA-Renderer. Warteschlange, Stelle, Zufall, Wiederholen wandern mit
  - `Connect.kt` Suche (SSDP) + Gerätebeschreibung + Sonos-Gruppen (ZoneGroupTopology), `Serve.kt` HTTP-Server mit Range, `RemotePlayer.kt` Media3-`SimpleBasePlayer` (AVTransport/RenderingControl, Abfrage jede Sekunde), `ConnectUi.kt` Auswahl
  - Lautstärketasten steuern den Lautsprecher (DeviceInfo REMOTE)
- Nur Heimnetz: INTERNET + CHANGE_WIFI_MULTICAST_STATE, keine Server im Internet, keine Konten, kein Tracking (Workflow prüft die Berechtigungsliste)

Build: nur über GitHub Actions (`.github/workflows/build.yml`). Ergebnis als Workflow-Artefakt: signiertes AAB (Play) und APK (Test).
Secret `KEYSTORE_B64` = Upload-Schlüssel für Play App Signing.

Store-Material: `store/`. Datenschutz: `DATENSCHUTZ.md`, `store/datenschutz.html`.
