# Datensicherheit (Data Safety) – Ausfüllhilfe

| Frage | Antwort |
|---|---|
| Erhebt oder teilt deine App erforderliche Nutzerdatentypen? | **Nein** |
| Werden alle erhobenen Nutzerdaten bei der Übertragung verschlüsselt? | entfällt (es werden keine Daten erhoben) |
| Können Nutzer die Löschung ihrer Daten beantragen? | entfällt – alle Daten liegen nur auf dem Gerät; „App-Daten löschen“ oder Deinstallieren entfernt alles |

Ergebnis im Store: **„Keine Daten erhoben“** und **„Keine Daten mit Dritten geteilt“**.

Begründung:
- Die App hat die Berechtigung INTERNET nur, weil Android sie für jede Socket-Verbindung verlangt – auch im eigenen WLAN. Sie verbindet sich ausschließlich mit Lautsprechern im lokalen Netz (private IP-Adressen), nie mit einem Server des Entwicklers oder Dritter. Kein SDK, keine Analyse, keine Absturzberichte.
- „Erhoben“ heißt bei Google: Daten verlassen das Gerät in Richtung Entwickler oder Dritte. Das passiert nicht.
- Das Abspielen auf einem Lautsprecher ist eine vom Nutzer ausgelöste Übertragung an sein eigenes Gerät (Google-Ausnahme „vom Nutzer initiierte Aktion“) und zählt nicht als Teilen.
- Gespeichert werden lokal nur: gewählte Ordner, Datei-Index (Dateinamen/Ordnerpfade), Hörbuch-Stellen, Einstellungen. Android-Backup ist deaktiviert (`allowBackup=false`).
