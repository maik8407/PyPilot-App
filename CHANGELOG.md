# Changelog PyPilot-App

Format: Keep a Changelog, deutsch.

## [1.4] - 2026-10-23
### Behoben
- „Zurück auf alten Kurs“ fährt den Ausgangskurs A an: A wird nur beim ersten Shortcut gemerkt (Vorsegel B, Großsegel C überschreiben ihn nicht) und erst nach erreichtem Zurück oder beim Ausschalten der Automatik gelöscht; ein Abbruch behält ihn. Tests für Wind von Steuerbord und Backbord, immer über die Windseite, nie durch 180°.
- Tasten ±1/±10 einheitlich mit Pfeil und Zahl (Pfeile gezeichnet, links Backbord, rechts Steuerbord); vorher fehlte bei +10 der Pfeil (Schriftzeichen nicht in jeder Schrift vorhanden).
- Update-Suche zeigt bei 404 (kein Release / Repo privat) die Ursache statt „aktuell“.
### Hinzugefügt
- Reiter Regler: pro Regelanteil eine Zeile, links Name, rechts festes Feld mit Mittelmarke, in dem der Balken läuft, dazu Zahlenwert; gemeinsame Skala aus dem Verlauf.
- Export der Regelparameter (Pilot, Profil, Werte mit Grenzen) als JSON-Text über das Android-Teilen-Menü.
- Karte: Home-Taste (eigene Position mittig); Ausschnitt und Zoom werden gespeichert und beim Start wiederverwendet; Kacheln dauerhaft auf dem Gerät (Arbeitsspeicher + Datei-Cache in `filesDir`, einmal geladen nie erneut), paralleles Laden.
- Karte und Kartenziel erscheinen nur im GPS-Modus.
- „Beenden“ in der Kopfzeile (zusätzlich zu Einstellungen und Benachrichtigung).
- GPS-Diagnose auf der Hauptseite: zeigt `gps.source` und `nmea.client` und erklärt, warum pypilot den GPS-Modus nicht anbietet (nur bei `gps.source` ≠ none, d. h. RMC mit Fix empfangen).
- Anzeige Controller-Temperatur (`servo.controller_temp`) und Servo-/Autopilot-Fehler (`servo.flags`, Namen aus pypilot/servo.py); fehlt ein Wert, steht „--“.

## [1.3] - 2026-10-23
### Behoben
- Automatik, Moduswahl und alle Befehle gingen nicht: `send()` schrieb auf dem Android-Hauptthread (NetworkOnMainThreadException), der Client schloss daraufhin die Verbindung. Befehle laufen jetzt über eine Warteschlange und einen eigenen IO-Schreiber.
- Protokoll gegen den echten pypilot-Quelltext (server.py, client.py, values.py, autopilot.py, pilots/) geprüft und angepasst: Profil heißt `profile`/`profiles` (nicht `ap.profile`); `ap.heartbeat` gibt es nicht (löste `error=invalid unknown value` aus), Keepalive ist eine Leerzeile; Regelparameter werden aus der Wertliste (`values`, `AutopilotGain`, min/max) gelesen und auf den erlaubten Bereich begrenzt (pypilot ignoriert Werte außerhalb); Pilot `basic` hat P, D, DD, PR, FF (kein I).
- Absturz im Reiter Regler: verschachteltes `verticalScroll` (Row mit Scroll in scrollender Spalte) führte zu unendlicher Höhe. Entfernt; fehlende pypilot-Werte zeigen nur „--“.
- Karte: Zwei-Finger-Zoom, Verschieben nur innerhalb der Kartenfläche (Clip), kein Überschieben über Menü/Reiter.
- Fehlermeldungen des pypilot-Servers (`error=...`) erscheinen in der Statuszeile.
### Geändert
- STOPP-Knopf entfernt. Bereitschaft (Automatik aus) genügt; sie beendet auch Shortcuts und Zielfahrt.
- Regelanteile kommen jetzt direkt von pypilot (`ap.pilot.<Pilot>.<Name>gain`) statt aus einer Näherung.
- Modus-Tasten sind nach `ap.modes` aktiv/grau; pypilot behält beim Moduswechsel den Kurs selbst bei.
- Vorschlag bei bleibender Abweichung betrifft jetzt PR (statt I).
- Tests: Fake-Server, der das Verhalten von server.py/values.py nachbildet (Python ist auf dem PC nicht vorhanden, kein echter pypilot-Lauf).

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
