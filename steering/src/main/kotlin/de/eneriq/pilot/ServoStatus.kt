package de.eneriq.pilot

/**
 * Auswertung von servo.flags (pypilot/servo.py, ServoFlags.get_str): ein String mit Leerzeichen getrennter Namen,
 * z. B. "SYNC ENGAGED" oder "OVERTEMP_FAULT PORT_OVERCURRENT_FAULT". Fehlt der Wert, gibt es keine Aussage (null).
 */
object ServoStatus {
    private val FAULT_WORDS = listOf("FAULT", "TIMEOUT", "INVALID", "BAD_")
    private val NOTE = setOf("SATURATED", "REBOOTED")

    /** Fehlerflags (z. B. OVERTEMP_FAULT, DRIVER_TIMEOUT); normale Zustaende wie SYNC/ENGAGED zaehlen nicht. */
    fun faults(flags: String?): List<String>? =
        flags?.trim()?.split(' ')?.filter { it.isNotEmpty() && FAULT_WORDS.any { w -> it.contains(w) } }

    /** Hinweise, die kein Fehler sind (Ruder am Anschlag, Neustart des Controllers). */
    fun notes(flags: String?): List<String> =
        flags?.trim()?.split(' ')?.filter { it in NOTE }.orEmpty()

    /** Temperatur: pypilot sendet `false`, solange der Controller keine meldet -> null. */
    fun temperature(raw: String?): Double? = raw?.trim()?.toDoubleOrNull()
}
