# Datensicherheit (Data Safety) – Ausfüllhilfe

| Frage | Antwort |
|---|---|
| Erhebt oder teilt deine App erforderliche Nutzerdatentypen? | **Nein** |
| Werden alle erhobenen Nutzerdaten bei der Übertragung verschlüsselt? | entfällt (es werden keine Daten erhoben) |
| Können Nutzer die Löschung ihrer Daten beantragen? | entfällt – alle Daten liegen nur auf dem Gerät; „App-Daten löschen“ oder Deinstallieren entfernt alles |

Ergebnis im Store: **„Keine Daten erhoben“** und **„Keine Daten mit Dritten geteilt“**.

Begründung:
- Die App hat die Berechtigung INTERNET nur, weil Android sie für jede Socket-Verbindung verlangt – auch im eigenen WLAN. Sie verbindet sich ausschließlich mit Geräten im lokalen Netz (Lautsprecher und Minimal Library auf dem eigenen Computer, private IP-Adressen), nie mit einem Server des Entwicklers oder Dritter. Kein SDK, keine Analyse, keine Absturzberichte, keine Werbung.
- Bereich „PC“: Titel, Playlisten und – auf Wunsch – Musikdateien gehen zwischen Handy und dem eigenen Computer des Nutzers hin und her (nach Koppeln per Code). Auch das ist eine vom Nutzer eingerichtete Übertragung an sein eigenes Gerät, nicht an den Entwickler.
- „Erhoben“ heißt bei Google: Daten verlassen das Gerät in Richtung Entwickler oder Dritte. Das passiert nicht.
- Das Abspielen auf einem Lautsprecher ist eine vom Nutzer ausgelöste Übertragung an sein eigenes Gerät (Google-Ausnahme „vom Nutzer initiierte Aktion“) und zählt nicht als Teilen.
- Gespeichert werden lokal nur: gewählte Ordner, Datei-Index (Dateinamen/Ordnerpfade), Hörbuch-Stellen, Einstellungen. Android-Backup ist deaktiviert (`allowBackup=false`).

Hinweis: Google wertet „Erheben“ als Übertragung vom Gerät weg. Die Einordnung oben (eigene Geräte des Nutzers, nichts an Entwickler/Dritte) ist bei lokalen Player-Apps üblich. Wer ganz auf Nummer sicher gehen will, kann zusätzlich „Audiodateien“ und „Dateien und Dokumente“ als *erhoben, nicht geteilt, optional, für App-Funktionen, nur auf eigene Geräte übertragen* angeben – dann steht im Store allerdings nicht mehr „Keine Daten erhoben“.
