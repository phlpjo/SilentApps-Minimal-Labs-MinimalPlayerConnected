# Datensicherheit (Data Safety) – Ausfüllhilfe

| Frage | Antwort |
|---|---|
| Erhebt oder teilt deine App erforderliche Nutzerdatentypen? | **Nein** |
| Werden alle erhobenen Nutzerdaten bei der Übertragung verschlüsselt? | entfällt (keine Übertragung) |
| Können Nutzer die Löschung ihrer Daten beantragen? | entfällt – alle Daten liegen nur auf dem Gerät; „App-Daten löschen“ oder Deinstallieren entfernt alles |

Ergebnis im Store: **„Keine Daten erhoben“** und **„Keine Daten mit Dritten geteilt“**.

Begründung: Die App hat keine INTERNET-Berechtigung (im Manifest ausdrücklich entfernt, auch für Bibliotheken). Gespeichert werden lokal nur: Liste der gewählten Ordner, Datei-Index (Dateinamen/Ordnerpfade), Hörbuch-Stellen, Einstellungen. Android-Backup ist deaktiviert (`allowBackup=false`), es wandert also auch nichts in Google-Backups.
