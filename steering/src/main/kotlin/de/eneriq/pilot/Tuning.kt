package de.eneriq.pilot

import kotlin.math.abs
import kotlin.math.sqrt

/** Ein Messpunkt des Mitschnitts. Winkel in Grad, t in Millisekunden. */
data class Sample(val t: Long, val heading: Double, val command: Double, val rudder: Double, val wind: Double? = null)

/** Reglerparameter von pypilot (ap.pilot.basic.*). */
data class Gains(val p: Double = 0.0, val i: Double = 0.0, val d: Double = 0.0, val dd: Double = 0.0, val ff: Double = 0.0) {
    companion object { val NAMES = listOf("P", "I", "D", "DD", "FF") }
}

/** Beitrag der Regelanteile zum Ruderbefehl (Naeherung nach der PID-Form; Ist-Beitraege liefert pypilot nicht direkt). */
data class Contribution(val p: Double, val i: Double, val d: Double, val dd: Double, val ff: Double) {
    val total get() = p + i + d + dd + ff
}

fun contribution(g: Gains, error: Double, errorInt: Double, rate: Double, accel: Double, ffTerm: Double = 0.0) =
    Contribution(g.p * error, g.i * errorInt, g.d * rate, g.dd * accel, g.ff * ffTerm)

data class Stats(
    val n: Int, val minutes: Double, val rms: Double, val mean: Double,
    val crossingsPerMin: Double, val rudderMeanAbs: Double, val rudderReversalsPerMin: Double,
)

data class Suggestion(val gain: String, val factor: Double, val reason: String) {
    override fun toString() = "$gain x${"%.2f".format(java.util.Locale.GERMANY, factor)}: $reason"
}

/** Auswertung eines Mitschnitts. Liefert nur Vorschlaege, wendet nie etwas an. */
object Tuning {
    const val MIN_SAMPLES = 60

    fun error(s: Sample, windMode: Boolean) =
        if (windMode) normalize180((s.wind ?: s.heading) - s.command) else normalize180(s.heading - s.command)

    fun stats(samples: List<Sample>, windMode: Boolean = false): Stats? {
        if (samples.size < 2) return null
        val e = samples.map { error(it, windMode) }
        val minutes = (samples.last().t - samples.first().t) / 60000.0
        if (minutes <= 0) return null
        val mean = e.average()
        val rms = sqrt(e.sumOf { it * it } / e.size)
        // Nulldurchgaenge des um den Mittelwert bereinigten Fehlers (Schwingung)
        var cross = 0
        for (k in 1 until e.size) if ((e[k - 1] - mean) * (e[k] - mean) < 0) cross++
        val r = samples.map { it.rudder }
        var rev = 0
        var lastDir = 0
        for (k in 1 until r.size) {
            val d = r[k] - r[k - 1]
            val dir = if (d > 0.3) 1 else if (d < -0.3) -1 else 0
            if (dir != 0) { if (lastDir != 0 && dir != lastDir) rev++; lastDir = dir }
        }
        return Stats(samples.size, minutes, rms, mean, cross / minutes, r.map { abs(it) }.average(), rev / minutes)
    }

    fun suggest(samples: List<Sample>, windMode: Boolean = false): List<Suggestion> {
        val st = stats(samples, windMode) ?: return emptyList()
        if (st.n < MIN_SAMPLES) return listOf(Suggestion("-", 1.0, "zu wenig Daten (${st.n} von ${MIN_SAMPLES} Messpunkten)"))
        val out = mutableListOf<Suggestion>()
        val f = { v: Double -> "%.1f".format(java.util.Locale.GERMANY, v) }
        when {
            st.crossingsPerMin > 6 && st.rms > 4 -> {
                out += Suggestion("P", 0.8, "Kurs schwingt (${f(st.crossingsPerMin)} Durchgaenge/min, Abweichung ${f(st.rms)} Grad): P senken")
                out += Suggestion("D", 1.2, "mehr Daempfung gegen das Schwingen")
            }
            st.rms > 8 && st.crossingsPerMin < 2 && abs(st.mean) < st.rms * 0.5 ->
                out += Suggestion("P", 1.25, "Pilot reagiert traege (Abweichung ${f(st.rms)} Grad, kaum Schwingung): P erhoehen")
        }
        if (abs(st.mean) > 3 && st.crossingsPerMin < 3)
            out += Suggestion("I", 1.3, "bleibende Abweichung von ${f(st.mean)} Grad: I erhoehen")
        if (st.rudderReversalsPerMin > 30 && st.rms < 3)
            out += Suggestion("DD", 0.8, "Ruder sehr unruhig (${f(st.rudderReversalsPerMin)} Richtungswechsel/min) bei guter Kursgenauigkeit: DD/D senken")
        if (out.isEmpty()) out += Suggestion("-", 1.0, "keine Auffaelligkeit (Abweichung ${f(st.rms)} Grad)")
        return out
    }
}

/** Ringpuffer fuer den Mitschnitt, ueber [csv] als Datei speicherbar. */
class Recorder(private val max: Int = 20000) {
    private val buf = ArrayDeque<Sample>()
    @Synchronized fun add(s: Sample) { buf.addLast(s); while (buf.size > max) buf.removeFirst() }
    @Synchronized fun samples(): List<Sample> = buf.toList()
    @Synchronized fun clear() = buf.clear()
    fun csv(): String = buildString {
        append("t_ms;kurs;soll;ruder;wind\n")
        for (s in samples()) append("${s.t};${s.heading};${s.command};${s.rudder};${s.wind ?: ""}\n")
    }
}
