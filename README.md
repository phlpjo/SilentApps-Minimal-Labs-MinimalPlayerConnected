# Minimal Player

Offline-Musik- und Hörbuchplayer im Stil von Minimal. Bezahl-App für Google Play – **privates Repo, keine Open-Source-Lizenz.**

- Hauptumschalter Musik / Hörbücher, je eigene Ordner (mehrere)
- Echte Ordneransicht statt ID3-Tags, natürliche Sortierung, Sofortsuche (Room-FTS-Index)
- Lückenlose Wiedergabe (ExoPlayer-Playlist)
- Hörbücher: Stelle je Ordner und je Datei, Tempo 1,0–2,5× mit Tonhöhenkorrektur, Schlaf-Timer mit Ausblenden und Schütteln, Zurück-/Vorspringen
- Sperrbildschirm, Benachrichtigung, Bluetooth-/Kopfhörer-Tasten (Media3-Session)
- Kein Internet (Berechtigung per Manifest entfernt), keine Konten, kein Tracking

Build: nur über GitHub Actions (`.github/workflows/build.yml`). Ergebnis als Workflow-Artefakt: signiertes AAB (Play) und APK (Test).
Secret `KEYSTORE_B64` = Upload-Schlüssel für Play App Signing.

Store-Material: `store/`. Datenschutz: `DATENSCHUTZ.md`, `store/datenschutz.html`.
