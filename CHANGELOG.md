# Changelog PyPilot-App

Format: Keep a Changelog, deutsch.

## [Unveröffentlicht]
### Hinzugefügt
- Modul `steering`: Lenklogik (Windwinkel mit Vorzeichen, Steuerbord/Backbord-Drehung, Maximalwinkel 170°, keine Seitenwechsel) mit 11 Tests, alle grün.
- `PypilotClient` (TCP 23322, `name=wert`, `watch`): Auto-Reconnect mit Backoff (1–15 s), Heartbeat `ap.heartbeat` alle 2 s, Totzeit-Erkennung nach 6 s; 4 Tests mit Fake-Server.
- Android-App Stufe 1 (`app`): Foreground-Service mit WLAN-Bindung, Modi Kompass/Wind/GPS, ±1/±10 (Steuerbord-Pfeil dreht immer nach Steuerbord, im Wind-Modus über `Steering`), einstellbarer Maximalwinkel (Vorgabe 170°), STOPP-Knopf, Verbindungsalarm (Alarmton nach 4 s ohne Verbindung).

### Hinweise
- Wind-Vorzeichen von pypilot ist ungeprüft: Konstante `Pypilot.WIND_SIGN` (Protocol.kt), erst am Boot testen.
- Einstellbar: pypilot-Adresse (Vorgabe 10.10.10.1), Maximalwinkel.
