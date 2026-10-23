package de.eneriq.pypilot

import android.content.Context
import de.eneriq.pilot.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
    val alarm: Boolean = false,
)

/** Gemeinsamer Zustand zwischen Service und Oberflaeche. Alle Lenkbefehle laufen hier durch. */
object PilotHub {
    val WATCH = listOf("ap.enabled", "ap.mode", "ap.heading", "ap.heading_command")

    private val _state = MutableStateFlow(PilotState())
    val state: StateFlow<PilotState> = _state

    @Volatile var client: PypilotClient? = null
    val steering = Steering()

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
                else -> n
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

    fun stop() {
        if (send(setLine("ap.enabled", "false"))) update { it.copy(message = "") }
    }

    fun engage(mode: Mode) {
        val s = _state.value
        if (!send(setString("ap.mode", mode.pypilot))) return
        // Aktuellen Wert als Sollwert uebernehmen, damit der Pilot nicht anluvt oder abfaellt.
        s.heading?.let { send(setLine("ap.heading_command", fmt(if (mode == Mode.WIND) windOut(windIn(it)) else it))) }
        send(setLine("ap.enabled", "true"))
        update { it.copy(message = "") }
    }

    /** toStarboard = Steuerbord-Pfeil: Boot dreht nach Steuerbord. */
    fun nudge(steps: Int, toStarboard: Boolean) {
        val s = _state.value
        val cmd = s.command ?: return update { it.copy(message = "Kein Sollwert") }
        if (!s.enabled) return update { it.copy(message = "Pilot ist aus") }
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

    private fun normalize360(a: Double) = ((a % 360.0) + 360.0) % 360.0
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    // Einstellungen
    fun prefs(c: Context) = c.getSharedPreferences("pypilot", Context.MODE_PRIVATE)
    fun host(c: Context) = prefs(c).getString("host", "10.10.10.1") ?: "10.10.10.1"
    fun maxAngle(c: Context) = prefs(c).getFloat("max", 170f).toDouble()
    fun saveSettings(c: Context, host: String, max: Double) {
        prefs(c).edit().putString("host", host).putFloat("max", max.toFloat()).apply()
        steering.maxAngle = max
        client?.let { it.host = host; it.reconnect() }
    }
}
