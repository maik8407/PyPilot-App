package de.eneriq.pypilot

import android.content.Context
import de.eneriq.pilot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

enum class Mode(val pypilot: String, val label: String) {
    COMPASS("compass", "Kompass"), WIND("wind", "Wind"), GPS("gps", "GPS");

    companion object { fun of(s: String) = entries.firstOrNull { it.pypilot == s } }
}

data class PilotState(
    val link: Link = Link.DISCONNECTED,
    val enabled: Boolean = false,
    val mode: Mode? = null,
    /** Aktueller Wert von ap.heading (im Wind-Modus: Windwinkel, siehe Pypilot.WIND_SIGN). */
    val heading: Double? = null,
    val command: Double? = null,
    val lastData: Long = 0,
    val message: String = "",
    // Regler
    val rudder: Double? = null,
    val headingRate: Double? = null,
    val errorInt: Double? = null,
    val profile: String? = null,
    val profiles: List<String> = emptyList(),
    val pilot: String = "basic",
    val gains: Map<String, Double> = emptyMap(),
    val contribution: Contribution? = null,
    val history: List<Contribution> = emptyList(),
    // Mitschnitt und Ziel
    val recording: Boolean = false,
    val samples: Int = 0,
    val target: Pair<Double, Double>? = null,
    val navInfo: String = "",
)

/** Gemeinsamer Zustand zwischen Service und Oberflaeche. Alle Lenkbefehle laufen hier durch. */
object PilotHub {
    /** Namen der Werte, die pypilot gesendet werden soll. Unbekannte Namen ignoriert pypilot. */
    val WATCH = listOf(
        "ap.enabled", "ap.mode", "ap.heading", "ap.heading_command", "ap.heading_error_int",
        "servo.command", "rudder.angle", "imu.headingrate", "ap.profile", "profiles", "ap.pilot",
    ) + Gains.NAMES.map { "ap.pilot.basic.$it" }

    private val _state = MutableStateFlow(PilotState())
    val state: StateFlow<PilotState> = _state

    @Volatile var client: PypilotClient? = null
    val steering = Steering()
    val recorder = Recorder()

    fun update(f: (PilotState) -> PilotState) = _state.update(f)

    fun onValue(name: String, value: String) {
        val now = System.currentTimeMillis()
        _state.update { s ->
            val n = s.copy(lastData = now)
            when (name) {
                "ap.enabled" -> n.copy(enabled = value.trim() == "true")
                "ap.mode" -> n.copy(mode = Mode.of(parseString(value)))
                "ap.heading" -> n.copy(heading = parseNumber(value))
                "ap.heading_command" -> n.copy(command = parseNumber(value))
                "ap.heading_error_int" -> n.copy(errorInt = parseNumber(value))
                "rudder.angle" -> n.copy(rudder = parseNumber(value))
                "servo.command" -> if (s.rudder == null) n.copy(rudder = parseNumber(value)?.times(30)) else n
                "imu.headingrate" -> n.copy(headingRate = parseNumber(value))
                "ap.profile" -> n.copy(profile = parseString(value))
                "ap.pilot" -> n.copy(pilot = parseString(value))
                "profiles" -> n.copy(profiles = runCatching {
                    JSONArray(value).let { a -> List(a.length()) { a.getString(it) } }
                }.getOrDefault(s.profiles))
                else -> {
                    val g = name.removePrefix("ap.pilot.basic.")
                    if (name.startsWith("ap.pilot.basic.") && g in Gains.NAMES)
                        parseNumber(value)?.let { v -> n.copy(gains = s.gains + (g to v)) } ?: n
                    else n
                }
            }
        }
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
    fun setProfile(name: String) { if (send(setString("ap.profile", name))) update { it.copy(profile = name) } }

    fun setGain(name: String, v: Double) {
        if (send(setLine("ap.pilot.basic.$name", fmt(v)))) update { it.copy(gains = it.gains + (name to v)) }
    }

    /** Einmal pro Sekunde: Beitraege der Regelanteile berechnen, Verlauf fortschreiben, ggf. mitschneiden. */
    fun startSampler() {
        if (samplerJob?.isActive == true) return
        samplerJob = hubScope.launch {
            while (isActive) {
                val s = _state.value
                val h = s.heading; val c = s.command
                if (h != null && c != null && s.link == Link.CONNECTED) {
                    val wind = s.mode == Mode.WIND
                    val err = if (wind) normalize180(h - c) else normalize180(h - c)
                    val g = s.gains
                    val contrib = contribution(
                        Gains(g["P"] ?: 0.0, g["I"] ?: 0.0, g["D"] ?: 0.0, g["DD"] ?: 0.0, g["FF"] ?: 0.0),
                        error = err, errorInt = s.errorInt ?: 0.0, rate = s.headingRate ?: 0.0, accel = 0.0)
                    if (s.recording) recorder.add(Sample(System.currentTimeMillis(), h, c, s.rudder ?: 0.0, if (wind) h else null))
                    update { it.copy(contribution = contrib, history = (it.history + contrib).takeLast(120), samples = recorder.samples().size) }
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

    /** STOPP: Shortcut abbrechen und Automatik sofort aus. */
    fun stop() {
        cancelNav()
        cancelShortcut()
        if (send(setLine("ap.enabled", "false"))) update { it.copy(message = "") }
    }

    /** Aktuellen Wert als Sollwert uebernehmen, damit das Boot nicht ruckartig dreht. */
    private fun takeOver(mode: Mode) {
        val h = _state.value.heading ?: return
        send(setLine("ap.heading_command", fmt(if (mode == Mode.WIND) windOut(windIn(h)) else h)))
    }

    /** Bereitschaft <-> Automatik. */
    fun toggleAuto() {
        val s = _state.value
        if (s.enabled) return stop()
        val mode = s.mode ?: Mode.COMPASS
        if (s.heading == null) return update { it.copy(message = "Kein Kurswert") }
        if (s.mode == null && !send(setString("ap.mode", mode.pypilot))) return
        takeOver(mode)
        shortcuts.forgetSaved()
        if (send(setLine("ap.enabled", "true"))) update { it.copy(message = "") }
    }

    fun setMode(mode: Mode) {
        cancelShortcut()
        shortcuts.forgetSaved()
        if (!send(setString("ap.mode", mode.pypilot))) return
        if (_state.value.enabled) takeOver(mode)
        update { it.copy(message = "") }
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
