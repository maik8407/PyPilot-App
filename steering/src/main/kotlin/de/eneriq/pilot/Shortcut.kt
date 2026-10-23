package de.eneriq.pilot

import kotlin.math.abs

sealed class Tick {
    object Idle : Tick()
    data class Step(val command: Double) : Tick()
    data class Done(val command: Double) : Tick()
    data class Aborted(val reason: String) : Tick()
}

/**
 * Shortcut im Wind-Modus (Vorsegel/Grosssegel einholen, zurueck): faehrt den Sollwinkel schrittweise an.
 * Alle Winkel mit Vorzeichen (+ = Wind von Steuerbord). Die Drehung bleibt auf der Windseite (nie durch
 * 180 Grad, nie auf die andere Seite) und bricht bei Seitenwechsel des Windes oder wenn das Boot dem
 * Sollwert nicht folgt ab. tick() wird einmal pro Sekunde aufgerufen.
 */
class ShortcutRunner(
    private val steering: Steering,
    private val stepDeg: Double = 10.0,
    private val maxDeviation: Double = 45.0,
) {
    private var target: Double? = null
    private var side: WindSide? = null

    /** Winkel (mit Vorzeichen) vor dem ersten Shortcut, fuer "Zurueck". */
    var saved: Double? = null
        private set

    private var returning = false

    val active get() = target != null

    fun start(currentWind: Double, commandSigned: Double, targetMag: Double): Result {
        val r = steering.goTo(currentWind, targetMag)
        if (r is Result.Ok) {
            if (saved == null) saved = commandSigned   // Ausgangskurs: nur beim ersten Shortcut merken, nie ueberschreiben
            returning = false
            target = r.target
            side = sideOf(currentWind)
        }
        return r
    }

    /** Zurueck auf den gespeicherten Winkel (auf der aktuellen Windseite). */
    fun back(currentWind: Double, commandSigned: Double): Result {
        val s = saved ?: return Result.Rejected("Kein gespeicherter Kurs")
        val r = start(currentWind, commandSigned, abs(s))
        if (r is Result.Ok) returning = true   // der gemerkte Kurs wird erst geloescht, wenn er erreicht ist
        return r
    }

    /** Gemerkten Kurs verwerfen (Automatik aus, Moduswechsel). */
    fun forgetSaved() { saved = null; returning = false }

    fun cancel() { target = null; side = null; returning = false }

    fun tick(currentWind: Double, commandSigned: Double): Tick {
        val t = target ?: return Tick.Idle
        if (sideOf(currentWind) != side) return abort("Windseite gewechselt")
        if (abs(currentWind - commandSigned) > maxDeviation) return abort("Boot folgt nicht")
        if (sideOf(commandSigned) != sideOf(t)) return abort("Seitenwechsel")
        val next = steering.nextStep(commandSigned, t, stepDeg)
        if (next == t) {
            if (returning) saved = null
            cancel(); return Tick.Done(next)
        }
        return Tick.Step(next)
    }

    private fun abort(reason: String): Tick { cancel(); return Tick.Aborted(reason) }
}
