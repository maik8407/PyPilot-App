package de.eneriq.pilot

object Pypilot {
    const val PORT = 23322

    /**
     * Vorzeichen des pypilot-Windwinkels gegenueber unserer Konvention (+ = Wind von Steuerbord).
     * UNGEPRUEFT: erst am Boot testen. Ist es falsch herum, hier auf -1.0 setzen, sonst nirgends.
     */
    const val WIND_SIGN = 1.0
}

data class Msg(val name: String, val value: String)

/** pypilot sendet Zeilen der Form `name=wert`. */
fun parseLine(line: String): Msg? {
    val i = line.indexOf('=')
    if (i <= 0) return null
    return Msg(line.substring(0, i).trim(), line.substring(i + 1).trim())
}

fun parseNumber(v: String): Double? = v.trim().toDoubleOrNull()

fun parseString(v: String): String = v.trim().removeSurrounding("\"")

fun watchLine(names: List<String>, periodSec: Double = 0.5): String =
    "watch={" + names.joinToString(",") { "\"$it\":$periodSec" } + "}"

fun setLine(name: String, value: String) = "$name=$value"
fun setString(name: String, value: String) = "$name=\"$value\""

/** Wartezeit zwischen Verbindungsversuchen: verdoppelt sich bis zum Maximum. */
class Backoff(private val baseMs: Long = 1000, private val maxMs: Long = 15000) {
    private var cur = baseMs
    fun next(): Long { val r = cur; cur = minOf(maxMs, cur * 2); return r }
    fun reset() { cur = baseMs }
}
