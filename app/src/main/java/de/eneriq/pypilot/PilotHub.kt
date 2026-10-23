package de.eneriq.pypilot

import android.content.Context
import de.eneriq.pilot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Betriebsarten; pypilot kennt "compass", "gps", "nav", "wind", "true wind" (autopilot.py, ModeProperty). */
enum class Mode(val pypilot: String, val label: String) {
    COMPASS("compass", "Kompass"), WIND("wind", "Wind"), GPS("gps", "GPS");

    companion object { fun of(s: String) = entries.firstOrNull { it.pypilot == s } }
}

data class PilotState(
    val link: Link = Link.DISCONNECTED,
    val enabled: Boolean = false,
    val mode: Mode? = null,
    /** pypilot-Modi, die der Pilot mit den vorhandenen Sensoren kann (ap.modes); leer = unbekannt. */
    val modes: List<String> = emptyList(),
    /** Aktueller Wert von ap.heading (im Wind-Modus: Windwinkel, siehe Pypilot.WIND_SIGN). */
    val heading: Double? = null,
    val command: Double? = null,
    val lastData: Long = 0,
    val message: String = "",
    // Regler
    val rudder: Double? = null,
    val profile: String? = null,
    val profiles: List<String> = emptyList(),
    val pilot: String? = null,
    val gainInfo: List<GainInfo> = emptyList(),
    val gains: Map<String, Double> = emptyMap(),
    /** Beitrag jedes Regelanteils zum Ruderbefehl, direkt von pypilot (ap.pilot.<pilot>.<Name>gain). */
    val terms: Map<String, Double> = emptyMap(),
    val history: List<Map<String, Double>> = emptyList(),
    // Mitschnitt und Ziel
    val recording: Boolean = false,
    val samples: Int = 0,
    val target: Pair<Double, Double>? = null,
    val navInfo: String = "",
)

/** Gemeinsamer Zustand zwischen Service und Oberflaeche. Alle Lenkbefehle laufen hier durch. */
object PilotHub {
    /** Namen, die pypilot dauerhaft gesendet werden sollen. Die Regelparameter kommen aus der Wertliste ("values"). */
    val WATCH = listOf(
        "ap.enabled", "ap.mode", "ap.modes", "ap.heading", "ap.heading_command", "ap.pilot",
        "rudder.angle", "profile", "profiles",
    )

    /** Beim Verbinden zu sendende Zeilen: Wertliste (einmalig bei Aenderung) und die Standardwerte. */
    val WATCH_LINES = listOf("watch={\"values\":true}", watchLine(WATCH, 0.5))

    private val _state = MutableStateFlow(PilotState())
    val state: StateFlow<PilotState> = _state

    @Volatile var client: PypilotClient? = null
    val steering = Steering()
    val recorder = Recorder()

    fun update(f: (PilotState) -> PilotState) = _state.update(f)

    fun onValue(name: String, value: String) {
        val now = System.currentTimeMillis()
        if (name == "error") { update { it.copy(message = "pypilot: ${value.take(80)}") }; return }
        if (name == "values") { onValuesList(value); return }
        _state.update { s ->
            val n = s.copy(lastData = now)
            when (name) {
                "ap.enabled" -> n.copy(enabled = value.trim() == "true")
                "ap.mode" -> n.copy(mode = Mode.of(parseString(value)))
                "ap.modes" -> n.copy(modes = Discovery.stringList(value))
                "ap.heading" -> n.copy(heading = parseNumber(value))
                "ap.heading_command" -> n.copy(command = parseNumber(value))
                "rudder.angle" -> n.copy(rudder = parseNumber(value))
                "profile" -> n.copy(profile = parseString(value))
                "profiles" -> n.copy(profiles = Discovery.stringList(value))
                "ap.pilot" -> n.copy(pilot = parseString(value))
                else -> {
                    val g = s.gainInfo.firstOrNull { it.valueName == name }
                    val t = s.gainInfo.firstOrNull { it.termName == name }
                    val v = parseNumber(value)
                    when {
                        v == null -> n
                        g != null -> n.copy(gains = s.gains + (g.name to v))
                        t != null -> n.copy(terms = s.terms + (t.name to v))
                        else -> n
                    }
                }
            }
        }
    }

    /** Wertliste von pypilot: Regelparameter des aktiven Piloten finden und zusaetzlich beobachten. */
    private fun onValuesList(json: String) {
        val all = Discovery.gains(json)
        val pilot = _state.value.pilot ?: all.firstOrNull { it.pilot == "basic" }?.pilot ?: all.firstOrNull()?.pilot ?: return
        val mine = all.filter { it.pilot == pilot }
        update { it.copy(gainInfo = mine) }
        val names = mine.flatMap { listOf(it.valueName, it.termName) }
        if (names.isNotEmpty()) client?.watch(watchLine(names, 0.5))   // erneutes Watch ist bei pypilot unschaedlich
    }

    // pypilot-Wert <-> unsere Konvention (+ = Wind von Steuerbord)
    private fun windIn(v: Double) = normalize180(v) * Pypilot.WIND_SIGN
    private fun windOut(v: Double) = normalize180(v * Pypilot.WIND_SIGN)

    private fun send(line: String): Boolean {
        val ok = client?.send(line) == true
        if (!ok) update { it.copy(message = "Nicht verbunden") }
        return ok
    }

    // --- Regler: Profil, Gains, Mitschnitt ---
    fun setProfile(name: String) { if (send(setString("profile", name))) update { it.copy(profile = name) } }

    /** Wert setzen; pypilot ignoriert Werte ausserhalb von min/max, deshalb hier begrenzen. */
    fun setGain(g: GainInfo, v: Double) {
        val c = v.coerceIn(g.min, g.max)
        if (send(setLine(g.valueName, fmt4(c)))) update { it.copy(gains = it.gains + (g.name to c)) }
    }

    /** Einmal pro Sekunde: Verlauf der Regelanteile fortschreiben, ggf. mitschneiden. */
    fun startSampler() {
        if (samplerJob?.isActive == true) return
        samplerJob = hubScope.launch {
            while (isActive) {
                val s = _state.value
                val h = s.heading; val c = s.command
                if (h != null && c != null && s.link == Link.CONNECTED) {
                    if (s.recording) recorder.add(Sample(System.currentTimeMillis(), h, c, s.rudder ?: 0.0, if (s.mode == Mode.WIND) h else null))
                    update { it.copy(history = (it.history + it.terms).takeLast(120), samples = recorder.samples().size) }
                }
                delay(1000)
            }
        }
    }

    fun setRecording(on: Boolean) { if (on && !_state.value.recording) recorder.clear(); update { it.copy(recording = on, samples = recorder.samples().size) } }

    fun suggestions(): List<Suggestion> = Tuning.suggest(recorder.samples(), _state.value.mode == Mode.WIND)

    fun saveRecording(c: Context): java.io.File {
        val f = java.io.File(c.getExternalFilesDir(null) ?: c.filesDir,
            "mitschnitt-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".csv")
        f.writeText(recorder.csv())
        return f
    }

    /** Automatik aus (Bereitschaft): Shortcut und Zielfahrt enden, der Pilot gibt das Ruder frei. */
    fun disengage() {
        cancelNav()
        cancelShortcut()
        if (send(setLine("ap.enabled", "false"))) update { it.copy(message = "") }
    }

    /** Bereitschaft <-> Automatik. Beim Einschalten wird der aktuelle Wert Sollwert, damit das Boot nicht ruckartig dreht. */
    fun toggleAuto() {
        val s = _state.value
        if (s.enabled) return disengage()
        val h = s.heading ?: return update { it.copy(message = "Kein Kurswert vom Pilot") }
        if (s.mode == null && !send(setString("ap.mode", Mode.COMPASS.pypilot))) return
        send(setLine("ap.heading_command", fmt(if (s.mode == Mode.WIND) windOut(windIn(h)) else h)))
        shortcuts.forgetSaved()
        if (send(setLine("ap.enabled", "true"))) update { it.copy(message = "") }
    }

    /** Modus wechseln; pypilot behaelt dabei den aktuellen Kurs selbst bei (autopilot.py, compute_heading_error). */
    fun setMode(mode: Mode) {
        cancelShortcut()
        shortcuts.forgetSaved()
        if (send(setString("ap.mode", mode.pypilot))) update { it.copy(message = "") }
    }

    /** toStarboard = Steuerbord-Pfeil: Boot dreht nach Steuerbord. */
    fun nudge(steps: Int, toStarboard: Boolean) {
        val s = _state.value
        val cmd = s.command ?: return update { it.copy(message = "Kein Sollwert") }
        if (!s.enabled) return update { it.copy(message = "Pilot ist aus") }
        cancelShortcut()
        shortcuts.forgetSaved()
        when (s.mode) {
            Mode.WIND -> when (val r = steering.nudge(windIn(cmd), steps.toDouble(), toStarboard)) {
                is Result.Ok -> { send(setLine("ap.heading_command", fmt(windOut(r.target)))); update { it.copy(message = "") } }
                is Result.Rejected -> update { it.copy(message = r.reason) }
            }
            Mode.COMPASS, Mode.GPS -> {
                val t = normalize360(cmd + if (toStarboard) steps else -steps)
                send(setLine("ap.heading_command", fmt(t)))
                update { it.copy(message = "") }
            }
            null -> update { it.copy(message = "Modus unbekannt") }
        }
    }

    // --- Shortcuts (nur Automatik + Wind-Modus) ---
    private val shortcuts = ShortcutRunner(steering)
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var shortcutJob: Job? = null
    private var samplerJob: Job? = null
    private var navJob: Job? = null

    fun shortcutsAvailable(s: PilotState) = s.enabled && s.mode == Mode.WIND && s.heading != null && s.command != null

    fun foresail(c: Context) = startShortcut(prefs(c).getFloat("foresail", 110f).toDouble())
    fun mainsail(c: Context) = startShortcut(prefs(c).getFloat("mainsail", 38f).toDouble())

    private fun startShortcut(targetMag: Double) {
        val s = _state.value
        if (!shortcutsAvailable(s)) return
        run(shortcuts.start(windIn(s.heading!!), windIn(s.command!!), targetMag))
    }

    fun backToOldCourse() {
        val s = _state.value
        if (!shortcutsAvailable(s)) return
        run(shortcuts.back(windIn(s.heading!!), windIn(s.command!!)))
    }

    private fun run(r: Result) {
        when (r) {
            is Result.Rejected -> update { it.copy(message = r.reason) }
            is Result.Ok -> {
                update { it.copy(message = "Shortcut laeuft ...") }
                shortcutJob?.cancel()
                shortcutJob = hubScope.launch {
                    while (isActive) {
                        val s = _state.value
                        if (!shortcutsAvailable(s)) { shortcuts.cancel(); update { it.copy(message = "Shortcut abgebrochen") }; break }
                        val t = shortcuts.tick(windIn(s.heading!!), windIn(s.command!!))
                        when (t) {
                            is Tick.Step -> send(setLine("ap.heading_command", fmt(windOut(t.command))))
                            is Tick.Done -> { send(setLine("ap.heading_command", fmt(windOut(t.command)))); update { it.copy(message = "Shortcut fertig") }; break }
                            is Tick.Aborted -> { update { it.copy(message = "ABBRUCH: ${t.reason}") }; break }
                            Tick.Idle -> break
                        }
                        delay(1000)
                    }
                }
            }
        }
    }

    fun cancelShortcut() {
        shortcutJob?.cancel(); shortcutJob = null
        shortcuts.cancel()
    }

    private fun normalize360(a: Double) = ((a % 360.0) + 360.0) % 360.0
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt4(v: Double) = String.format(java.util.Locale.US, "%.4f", v)

    // Einstellungen
    fun prefs(c: Context) = c.getSharedPreferences("pypilot", Context.MODE_PRIVATE)
    fun host(c: Context) = prefs(c).getString("host", "10.10.10.1") ?: "10.10.10.1"
    fun maxAngle(c: Context) = prefs(c).getFloat("max", 170f).toDouble()
    fun foresailAngle(c: Context) = prefs(c).getFloat("foresail", 110f).toDouble()
    fun mainsailAngle(c: Context) = prefs(c).getFloat("mainsail", 38f).toDouble()
    fun saveSettings(c: Context, host: String, max: Double, foresail: Double, mainsail: Double) {
        prefs(c).edit().putString("host", host).putFloat("max", max.toFloat())
            .putFloat("foresail", foresail.toFloat()).putFloat("mainsail", mainsail.toFloat()).apply()
        steering.maxAngle = max
        client?.let { it.host = host; it.reconnect() }
    }

    // --- Zielanfahrt (GPS-Modus): Kurs zum Ziel nachfuehren, Position vom Handy ---
    fun startNav(lat: Double, lon: Double, position: () -> Pair<Double, Double>?) {
        cancelNav()
        val g = GoTo(lat, lon)
        update { it.copy(target = lat to lon, navInfo = "") }
        if (_state.value.mode != Mode.GPS) setMode(Mode.GPS)
        navJob = hubScope.launch {
            while (isActive) {
                val s = _state.value
                val pos = position()
                when {
                    pos == null -> update { it.copy(navInfo = "Keine Position vom Handy-GPS") }
                    !s.enabled || s.mode != Mode.GPS -> update { it.copy(navInfo = "Automatik im GPS-Modus einschalten") }
                    else -> when (val o = g.update(pos.first, pos.second)) {
                        GoTo.Out.Arrived -> { update { it.copy(navInfo = "Ziel erreicht", target = null, message = "Ziel erreicht") }; break }
                        is GoTo.Out.Course -> {
                            if (o.changed) send(setLine("ap.heading_command", fmt(o.deg)))
                            update { it.copy(navInfo = String.format(java.util.Locale.GERMANY, "Kurs %.0f°  Entfernung %.2f sm", o.deg, o.distNm)) }
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    fun cancelNav() {
        navJob?.cancel(); navJob = null
        update { it.copy(target = null, navInfo = "") }
    }

    /** Beim Beenden des Service: alle Hintergrundjobs stoppen. */
    fun shutdown() {
        cancelShortcut(); cancelNav()
        samplerJob?.cancel(); samplerJob = null
    }
}
