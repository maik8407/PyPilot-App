# Changelog PyPilot-App

Format: Keep a Changelog, deutsch.

## [1.2] - 2026-10-23
### Geändert
- Auto-Update jetzt über GitHub-Releases im eigenen Repo (`maik8407/PyPilot-App`, Konstante `AppUpdater.REPO`): `releases/latest`, Version aus `tag_name`, APK-Asset wird geladen. Kein Eintrag mehr in EnerIQ-firmware nötig. Versionsvergleich als `Version` mit Tests.

## [1.1] - 2026-10-23
### Behoben
- Mehrfacher Dauer-Alarm beim Start: Der Alarmton wurde bei jedem Statuswechsel neu gestartet, ohne den vorherigen zu stoppen (nur der letzte wurde beendet), und der Service konnte mehrfach starten. Der Alarm ist komplett entfernt, der Verbindungsstatus erscheint nur noch in der Oberfläche.
- App ließ sich nicht schließen: Service lief mit `START_STICKY` weiter und baute die Verbindung neu auf. Jetzt endet alles (Service, Sockets, Reconnect-Schleife, Benachrichtigung) bei Zurück, bei Wischen aus der Übersicht (`onTaskRemoved`), über „Beenden“ in der Benachrichtigung und im Einstellungsmenü. Test: Client verbindet nach `stop()` nicht mehr.
### Hinzugefügt
- Taste Bereitschaft ⇄ Automatik; beim Einschalten wird der aktuelle Kurs/Windwinkel als Sollwert übernommen. STOPP auf jedem Reiter.
- Wind-Shortcuts (nur Automatik + Wind): Vorsegel einholen (Vorgabe 110°), Großsegel einholen (Vorgabe 38°), Zurück auf alten Kurs. Schrittweise 10°/s, immer auf der Windseite, nie über den Maximalwinkel, Abbruch bei Windseitenwechsel oder wenn das Boot nicht folgt; STOPP bricht ab. 10 Tests.
- Reiter Regler: Profil (`ap.profile`) wählen, Gains P/I/D/DD/FF um ±10 % ändern, Live-Balken und Verlauf der Regelanteile (Näherung aus Gains und Messwerten), Datenmitschnitt mit CSV-Export und Vorschlägen zur Parameteranpassung (Heuristik mit Begründung, wird nie automatisch angewendet).
- Reiter Karte (OpenStreetMap-Kacheln wie in EnerIQ-Handy, mit Cache): Ziel antippen, „Anfahren“ führt den Kurs im GPS-Modus nach (Position vom Handy-GPS, Hysterese 2°, Ankunft bei 0,05 sm).
- Auto-Update (in 1.2 auf GitHub-Releases umgestellt).
- Einstellbar: Adresse, Maximalwinkel, Shortcut-Winkel.
### Geändert
- Version 1.1 (versionCode 2).

## [Unveröffentlicht → 0.1]
### Hinzugefügt
- Modul `steering`: Lenklogik mit 11 Tests. `PypilotClient` (TCP 23322, `name=wert`, `watch`) mit Auto-Reconnect, Heartbeat alle 2 s, Totzeit-Erkennung.
- Android-App Stufe 1: Foreground-Service, Modi Kompass/Wind/GPS, ±1/±10, STOPP.

### Hinweise
- Wind-Vorzeichen von pypilot ist ungeprüft: Konstante `Pypilot.WIND_SIGN` (Protocol.kt), erst am Boot testen.
- pypilot-Namen für Regler (`ap.pilot.basic.P` …, `ap.heading_error_int`, `rudder.angle`, `imu.headingrate`, `profiles`) sind nach Kenntnis des Protokolls gesetzt und gegen echtes pypilot ungetestet.
