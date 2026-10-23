package de.eneriq.pypilot

import org.json.JSONArray
import org.json.JSONObject

data class GainInfo(val pilot: String, val name: String, val min: Double, val max: Double) {
    /** Wert, der gelesen und gesetzt wird: ap.pilot.<pilot>.<name> */
    val valueName get() = "ap.pilot.$pilot.$name"
    /** Sensorwert mit dem Beitrag dieses Anteils zum Ruderbefehl (pypilot: Name + "gain"). */
    val termName get() = "ap.pilot.$pilot.${name}gain"
}

/** Auswertung der Nachrichten, die pypilot in eigener Form sendet (siehe pypilot/server.py, values.py). */
object Discovery {
    private val GAIN = Regex("""^ap\.pilot\.([^.]+)\.([^.]+)$""")

    /** `values={"name": {info}, ...}`: alle Regelparameter (info.AutopilotGain) mit Grenzen. */
    fun gains(valuesJson: String): List<GainInfo> {
        val o = runCatching { JSONObject(valuesJson) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<GainInfo>()
        for (k in o.keys()) {
            val m = GAIN.matchEntire(k) ?: continue
            val info = o.optJSONObject(k) ?: continue
            if (!info.optBoolean("AutopilotGain")) continue
            out += GainInfo(m.groupValues[1], m.groupValues[2], info.optDouble("min", 0.0), info.optDouble("max", Double.MAX_VALUE))
        }
        return out.sortedBy { it.name }
    }

    /** JSON-Liste von Strings, z. B. profiles=["default","segeln"] oder ap.modes. */
    fun stringList(json: String): List<String> = runCatching {
        JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }
    }.getOrDefault(emptyList())
}
